import { randomUUID } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { isIP } from "node:net";
import type { ServerReleaseManifest } from "../server-release.js";
import { AccountService } from "./account-service.js";
import { AccountControlService } from "./account-control-service.js";
import { EmailOtpService } from "./email-otp-service.js";
import { normalizeEmailAddress } from "./email-otp.js";
import {
  ACCOUNT_WEB_APP_CSS,
  ACCOUNT_WEB_APP_JS,
  ACCOUNT_WEB_SHELL,
} from "./account-web-shell.js";
import { AccountSharingService } from "./account-sharing-service.js";
import { WebSessionSecurity } from "./web-session-security.js";
import {
  AccountModeError,
  accountErrors,
  type AccountPlatform,
  type ReauthenticationScope,
} from "./model.js";

const MAX_ACCOUNT_BODY_BYTES = 32 * 1024;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface AccountCapabilities {
  version: 1;
  accountAuth: {
    enabled: boolean;
    providers: Array<"google" | "email_otp">;
    android: boolean;
    macos: boolean;
    identityManagement: boolean;
    accountDeletion?: true;
    webAccountCenter: boolean;
    webSessions?: true;
  };
  binding: {
    enabled: boolean;
    replacement: boolean;
    maxActiveConnectorsPerAccount: 1 | 3;
    supportsDeviceSelection?: true;
    supportsDeviceSharing?: true;
    maxSharedDevices?: 10;
    maxGranteesPerDevice?: 5;
  };
  legacy: {
    appTokenAccepted: boolean;
    connectorTokenAccepted: boolean;
  };
  desktopBootstrap?: {
    runtimeContract: "hermes-serve-v1";
  };
  server?: {
    version: string;
    protocolVersions: ServerReleaseManifest["protocolVersions"];
    minimumClients: ServerReleaseManifest["minimumClients"];
  };
}

export class AccountHttpController {
  private readonly exchangeLimiter = new FixedWindowLimiter(10, 60_000);
  private readonly refreshLimiter = new FixedWindowLimiter(60, 60_000);

  constructor(
    private readonly enabled: boolean,
    private readonly service?: AccountService,
    private readonly options: {
      trustLoopbackProxy?: boolean;
      controlEnabled?: boolean;
      controlService?: AccountControlService;
      emailOtpEnabled?: boolean;
      googleAuthEnabled?: boolean;
      emailOtpService?: EmailOtpService;
      identityManagementEnabled?: boolean;
      accountDeletionEnabled?: boolean;
      webAccountCenterEnabled?: boolean;
      webSessionEnabled?: boolean;
      webSessionSecurity?: WebSessionSecurity;
      googleWebClientId?: string;
      multiDeviceEnabled?: boolean;
      sharingEnabled?: boolean;
      desktopManagedInstallEnabled?: boolean;
      sharingService?: AccountSharingService;
      serverRelease?: ServerReleaseManifest;
    } = {},
  ) {}

  async handle(request: IncomingMessage, response: ServerResponse, url: URL): Promise<void> {
    const correlationId = randomUUID();
    try {
      if (url.pathname === "/v2/capabilities" && request.method === "GET") {
        sendJson(response, 200, capabilities(
          this.enabled,
          Boolean(this.options.controlEnabled),
          Boolean(this.options.emailOtpEnabled),
          this.googleAuthEnabled(),
          Boolean(this.options.identityManagementEnabled),
          Boolean(this.options.accountDeletionEnabled),
          Boolean(this.options.webAccountCenterEnabled),
          Boolean(this.options.webSessionEnabled),
          Boolean(this.options.multiDeviceEnabled),
          Boolean(this.options.sharingEnabled),
          Boolean(this.options.desktopManagedInstallEnabled),
          this.options.serverRelease,
        ), {
          "cache-control": "public, max-age=60",
        });
        return;
      }
      if (url.pathname === "/account" && request.method === "GET") {
        if (this.enabled && this.options.webAccountCenterEnabled) {
          sendAccountWebShell(response, this.googleAuthEnabled());
          return;
        }
        throw new AccountModeError(
          404,
          "HR-ACCOUNT-004",
          "The account endpoint was not found.",
          false,
          "none",
        );
      }
      if (url.pathname === "/account/assets/account.css" && request.method === "GET") {
        if (this.enabled && this.options.webAccountCenterEnabled) {
          sendAccountWebAsset(response, "text/css; charset=utf-8", ACCOUNT_WEB_APP_CSS);
          return;
        }
        throw accountErrors.resourceNotFound();
      }
      if (url.pathname === "/account/assets/account.js" && request.method === "GET") {
        if (this.enabled && this.options.webAccountCenterEnabled) {
          sendAccountWebAsset(response, "text/javascript; charset=utf-8", ACCOUNT_WEB_APP_JS);
          return;
        }
        throw accountErrors.resourceNotFound();
      }
      if (!this.enabled || !this.service) throw accountErrors.featureDisabled();

      if (url.pathname === "/v2/web/session" && request.method === "GET") {
        const web = this.requireWebSession();
        const state = web.bootstrap(request);
        let principal;
        let accountDeletionPending = false;
        try {
          principal = await this.service.authenticate(web.authorization(request));
        } catch (error) {
          if (!(error instanceof AccountModeError)
              || !["HR-AUTH-003", "HR-AUTH-004", "HR-ACCOUNT-012"].includes(error.code)) throw error;
          accountDeletionPending = error.code === "HR-ACCOUNT-012";
        }
        sendJson(response, 200, {
          session: principal
            ? {
                authenticated: true,
                account: principal.account,
                installation: principal.installation,
              }
            : {
                authenticated: false,
                ...(accountDeletionPending ? { accountDeletionPending: true } : {}),
              },
          csrfToken: state.csrfToken,
          authentication: {
            google: this.googleAuthEnabled() && this.options.googleWebClientId
              ? { clientId: this.options.googleWebClientId }
              : null,
          },
          features: {
            accountDeletion: Boolean(this.options.accountDeletionEnabled),
          },
        }, {
          "set-cookie": accountDeletionPending
            ? [...web.clearCookies(), ...state.cookies]
            : state.cookies,
        });
        return;
      }

      if (url.pathname === "/v2/web/auth/email/challenges" && request.method === "POST") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const emailOtp = this.requireEmailOtp();
        const body = await readJsonObject(request);
        const challenge = await emailOtp.requestChallenge({
          email: boundedString(body.email, "email", 3, 254),
          purpose: "sign_in",
          platform: "web",
          source: this.sourceKey(request),
          clientInstallationId: state.installationId,
        });
        sendJson(response, 202, { challenge }, { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/auth/email/exchange" && request.method === "POST") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        this.exchangeLimiter.requireAllowance(this.sourceKey(request));
        const emailOtp = this.requireEmailOtp();
        const body = await readJsonObject(request);
        const idempotencyKey = uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key");
        const identity = await emailOtp.verifyChallenge({
          challengeId: uuid(body.challengeId, "challengeId"),
          email: boundedString(body.email, "email", 3, 254),
          code: boundedString(body.code, "code", 6, 6),
          purpose: "sign_in",
          platform: "web",
          clientInstallationId: state.installationId,
          exchangeIdempotencyKey: idempotencyKey,
        });
        const result = await this.service.exchangeEmailIdentity(identity, {
          platform: "web",
          clientInstallationId: state.installationId,
          displayName: optionalWebDisplayName(body.displayName),
          appVersion: this.options.serverRelease?.serverVersion ?? "web",
          idempotencyKey,
        });
        sendJson(response, 200, publicWebSession(result), {
          "set-cookie": [...state.cookies, ...web.sessionCookies(result.session)],
        });
        return;
      }

      if (url.pathname === "/v2/web/auth/google/exchange" && request.method === "POST"
          && this.googleAuthEnabled()) {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        this.exchangeLimiter.requireAllowance(this.sourceKey(request));
        const body = await readJsonObject(request);
        const result = await this.service.exchangeGoogleProof({
          platform: "web",
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          clientInstallationId: state.installationId,
          displayName: optionalWebDisplayName(body.displayName),
          appVersion: this.options.serverRelease?.serverVersion ?? "web",
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, publicWebSession(result), {
          "set-cookie": [...state.cookies, ...web.sessionCookies(result.session)],
        });
        return;
      }

      if (url.pathname === "/v2/web/auth/refresh" && request.method === "POST") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        this.refreshLimiter.requireAllowance(this.sourceKey(request));
        const session = await this.service.refresh({
          refreshToken: web.refreshToken(request),
          clientInstallationId: state.installationId,
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, {
          session: {
            authenticated: true,
            accessExpiresAt: session.accessExpiresAt,
            refreshExpiresAt: session.refreshExpiresAt,
          },
          csrfToken: state.csrfToken,
        }, { "set-cookie": [...state.cookies, ...web.sessionCookies(session)] });
        return;
      }

      if (url.pathname === "/v2/web/account" && request.method === "GET") {
        const web = this.requireWebSession();
        const principal = await this.service.authenticate(web.authorization(request));
        sendJson(response, 200, {
          account: principal.account,
          installation: principal.installation,
          session: { authenticated: true },
        });
        return;
      }

      if (url.pathname === "/v2/web/account" && request.method === "DELETE"
          && this.options.accountDeletionEnabled) {
        const web = this.requireWebSession();
        web.requireMutation(request);
        const body = await readJsonObject(request);
        await this.service.requestAccountDeletion(
          web.authorization(request),
          boundedString(body.grant, "grant", 1, 256),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
          exactBoolean(
            body.acknowledgedPermanentCloudDeletion,
            "acknowledgedPermanentCloudDeletion",
          ),
        );
        sendNoContent(response, { "set-cookie": web.clearCookies() });
        return;
      }

      if (url.pathname === "/v2/web/installations" && request.method === "GET") {
        const web = this.requireWebSession();
        const security = this.requireAccountSecurity();
        const principal = await this.service.authenticate(web.authorization(request));
        sendJson(response, 200, {
          items: await security.listAccountInstallations(principal),
        });
        return;
      }

      const webInstallationMatch = /^\/v2\/web\/installations\/([0-9a-f-]{36})$/i
        .exec(url.pathname);
      if (webInstallationMatch && request.method === "DELETE") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const security = this.requireAccountSecurity();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        await security.revokeAccountInstallation(
          principal,
          uuid(webInstallationMatch[1], "installationId"),
          boundedString(body.grant, "grant", 1, 256),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendNoContent(response, { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/audit-events" && request.method === "GET") {
        const web = this.requireWebSession();
        const security = this.requireAccountSecurity();
        const principal = await this.service.authenticate(web.authorization(request));
        sendJson(response, 200, {
          items: await security.listAccountAuditEvents(principal, 50),
        });
        return;
      }

      if (url.pathname === "/v2/web/identities" && request.method === "GET") {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const principal = await this.service.authenticate(web.authorization(request));
        sendJson(response, 200, { items: await this.service.listExternalIdentities(principal) });
        return;
      }

      if (url.pathname === "/v2/web/identities/email/challenges" && request.method === "POST") {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const challenge = await emailOtp.requestChallenge({
          email: boundedString(body.email, "email", 3, 254),
          purpose: "link_identity",
          platform: "web",
          source: this.sourceKey(request),
          clientInstallationId: principal.installation.id,
        });
        sendJson(response, 202, { challenge }, { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/identities/email" && request.method === "POST") {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const idempotencyKey = uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key");
        const identity = await emailOtp.verifyChallenge({
          challengeId: uuid(body.challengeId, "challengeId"),
          email: boundedString(body.email, "email", 3, 254),
          code: boundedString(body.code, "code", 6, 6),
          purpose: "link_identity",
          platform: "web",
          clientInstallationId: principal.installation.id,
          exchangeIdempotencyKey: idempotencyKey,
        });
        const linked = await this.service.linkEmailIdentity(principal, identity, {
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey,
        });
        sendJson(response, 200, { identity: linked }, { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/identities/google" && request.method === "POST"
          && this.googleAuthEnabled()) {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const linked = await this.service.linkGoogleIdentity(principal, {
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { identity: linked }, { "set-cookie": state.cookies });
        return;
      }

      const webIdentityMatch = /^\/v2\/web\/identities\/([0-9a-f-]{36})$/i.exec(url.pathname);
      if (webIdentityMatch && request.method === "DELETE") {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const result = await this.service.unlinkIdentity(principal, {
          identityId: uuid(webIdentityMatch[1], "identityId"),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, result, {
          "set-cookie": result.currentSessionRevoked ? web.clearCookies() : state.cookies,
        });
        return;
      }

      if (url.pathname === "/v2/web/devices" && request.method === "GET") {
        if (!this.options.multiDeviceEnabled) throw accountErrors.bindingFeatureDisabled();
        const web = this.requireWebSession();
        const control = this.requireControl();
        const principal = await this.service.authenticate(web.authorization(request));
        sendJson(response, 200, {
          items: await control.listDevices(principal),
          maxOwnedDevices: 3,
        });
        return;
      }

      const webDefaultDeviceMatch = /^\/v2\/web\/devices\/([^/]+)\/select-default$/
        .exec(url.pathname);
      if (webDefaultDeviceMatch && request.method === "POST") {
        if (!this.options.multiDeviceEnabled) throw accountErrors.bindingFeatureDisabled();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const control = this.requireControl();
        const principal = await this.service.authenticate(web.authorization(request));
        const device = await control.selectDefaultDevice(
          principal,
          decodedPathSegment(webDefaultDeviceMatch[1], "deviceId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendJson(response, 200, { device }, { "set-cookie": state.cookies });
        return;
      }

      const webSharesMatch = /^\/v2\/web\/devices\/([^/]+)\/shares$/.exec(url.pathname);
      if (webSharesMatch && request.method === "GET") {
        const web = this.requireWebSession();
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(web.authorization(request));
        sendJson(response, 200, await sharing.listShares(
          principal,
          decodedPathSegment(webSharesMatch[1], "deviceId"),
        ));
        return;
      }

      if (url.pathname === "/v2/web/auth/reauth/email/challenges" && request.method === "POST") {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const email = normalizedEmailInput(body.email);
        const identities = await this.service.listExternalIdentities(principal);
        if (!identities.some((identity) => identity.provider === "email_otp"
          && identity.email === email)) {
          throw accountErrors.reauthenticationRequired();
        }
        const challenge = await emailOtp.requestChallenge({
          email,
          purpose: "reauthenticate",
          platform: "web",
          source: this.sourceKey(request),
          clientInstallationId: principal.installation.id,
        });
        sendJson(response, 202, { challenge }, { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/auth/reauth/email" && request.method === "POST") {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const idempotencyKey = uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key");
        const scope = reauthenticationScope(body.scope);
        const identity = await emailOtp.verifyChallenge({
          challengeId: uuid(body.challengeId, "challengeId"),
          email: boundedString(body.email, "email", 3, 254),
          code: boundedString(body.code, "code", 6, 6),
          purpose: "reauthenticate",
          platform: "web",
          clientInstallationId: principal.installation.id,
          exchangeIdempotencyKey: idempotencyKey,
        });
        sendJson(response, 200, await this.service.reauthenticateEmailIdentity(
          principal,
          identity,
          { scope, idempotencyKey },
        ), { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/auth/reauth/google" && request.method === "POST"
          && this.googleAuthEnabled()) {
        this.requireIdentityManagement();
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const result = await this.service.reauthenticateGoogle(principal, {
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          scope: reauthenticationScope(body.scope),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, result, { "set-cookie": state.cookies });
        return;
      }

      const webInvitationCollectionMatch = /^\/v2\/web\/devices\/([^/]+)\/share-invitations$/
        .exec(url.pathname);
      if (webInvitationCollectionMatch && request.method === "POST") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const invitation = await sharing.createInvitation(principal, {
          deviceId: decodedPathSegment(webInvitationCollectionMatch[1], "deviceId"),
          email: boundedString(body.email, "email", 3, 254),
          grant: boundedString(body.grant, "grant", 1, 256),
          acknowledgedWholeDeviceAccess: exactBoolean(
            body.acknowledgedWholeDeviceAccess,
            "acknowledgedWholeDeviceAccess",
          ),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 202, { invitation }, { "set-cookie": state.cookies });
        return;
      }

      const webInvitationMatch = /^\/v2\/web\/devices\/([^/]+)\/share-invitations\/([0-9a-f-]{36})$/i
        .exec(url.pathname);
      if (webInvitationMatch && request.method === "DELETE") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(web.authorization(request));
        await sharing.cancelInvitation(
          principal,
          decodedPathSegment(webInvitationMatch[1], "deviceId"),
          uuid(webInvitationMatch[2], "invitationId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendNoContent(response, { "set-cookie": state.cookies });
        return;
      }

      const webShareGrantMatch = /^\/v2\/web\/devices\/([^/]+)\/shares\/([0-9a-f-]{36})$/i
        .exec(url.pathname);
      if (webShareGrantMatch && request.method === "DELETE") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(web.authorization(request));
        await sharing.revokeGrant(
          principal,
          decodedPathSegment(webShareGrantMatch[1], "deviceId"),
          uuid(webShareGrantMatch[2], "grantId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendNoContent(response, { "set-cookie": state.cookies });
        return;
      }

      const webAcceptInvitationMatch = /^\/v2\/web\/share-invitations\/(hsi_[A-Za-z0-9_-]{43})\/accept$/
        .exec(url.pathname);
      if (webAcceptInvitationMatch && request.method === "POST") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(web.authorization(request));
        const body = await readJsonObject(request);
        const device = await sharing.acceptInvitation(
          principal,
          webAcceptInvitationMatch[1],
          exactBoolean(body.acknowledgedWholeDeviceAccess, "acknowledgedWholeDeviceAccess"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendJson(response, 200, { device }, { "set-cookie": state.cookies });
        return;
      }

      const webLeaveMatch = /^\/v2\/web\/devices\/([^/]+)\/leave$/.exec(url.pathname);
      if (webLeaveMatch && request.method === "POST") {
        const web = this.requireWebSession();
        const state = web.requireMutation(request);
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(web.authorization(request));
        await sharing.leaveDevice(
          principal,
          decodedPathSegment(webLeaveMatch[1], "deviceId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendNoContent(response, { "set-cookie": state.cookies });
        return;
      }

      if (url.pathname === "/v2/web/auth/sign-out" && request.method === "POST") {
        const web = this.requireWebSession();
        web.requireMutation(request);
        await this.service.signOut(
          web.authorization(request),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, {
          "cache-control": "no-store",
          "set-cookie": web.clearCookies(),
        });
        response.end();
        return;
      }

      if (url.pathname === "/v2/auth/email/challenges" && request.method === "POST") {
        const emailOtp = this.requireEmailOtp();
        const body = await readJsonObject(request);
        const result = await emailOtp.requestChallenge({
          email: boundedString(body.email, "email", 3, 254),
          purpose: "sign_in",
          platform: accountPlatform(body.platform),
          source: this.sourceKey(request),
          clientInstallationId: uuid(body.clientInstallationId, "clientInstallationId"),
        });
        sendJson(response, 202, { challenge: result });
        return;
      }

      if (url.pathname === "/v2/auth/email/exchange" && request.method === "POST") {
        this.exchangeLimiter.requireAllowance(this.sourceKey(request));
        const emailOtp = this.requireEmailOtp();
        const body = await readJsonObject(request);
        const platform = accountPlatform(body.platform);
        const clientInstallationId = uuid(body.clientInstallationId, "clientInstallationId");
        const idempotencyKey = uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key");
        const identity = await emailOtp.verifyChallenge({
          challengeId: uuid(body.challengeId, "challengeId"),
          email: boundedString(body.email, "email", 3, 254),
          code: boundedString(body.code, "code", 6, 6),
          purpose: "sign_in",
          platform,
          clientInstallationId,
          exchangeIdempotencyKey: idempotencyKey,
        });
        const result = await this.service.exchangeEmailIdentity(identity, {
          platform,
          clientInstallationId,
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          appVersion: boundedDisplayString(body.appVersion, "appVersion", 64),
          idempotencyKey,
        });
        sendJson(response, 200, result);
        return;
      }

      if (url.pathname === "/v2/account/identities" && request.method === "GET") {
        this.requireIdentityManagement();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, { items: await this.service.listExternalIdentities(principal) });
        return;
      }

      if (url.pathname === "/v2/auth/reauth/email/challenges" && request.method === "POST") {
        this.requireIdentityManagement();
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const email = normalizedEmailInput(body.email);
        const identities = await this.service.listExternalIdentities(principal);
        if (!identities.some((identity) => identity.provider === "email_otp"
          && identity.email === email)) {
          throw accountErrors.reauthenticationRequired();
        }
        const result = await emailOtp.requestChallenge({
          email,
          purpose: "reauthenticate",
          platform: principal.installation.platform,
          source: this.sourceKey(request),
          clientInstallationId: principal.installation.id,
        });
        sendJson(response, 202, { challenge: result });
        return;
      }

      if (url.pathname === "/v2/auth/reauth/email" && request.method === "POST") {
        this.requireIdentityManagement();
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const idempotencyKey = uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key");
        const scope = reauthenticationScope(body.scope);
        const identity = await emailOtp.verifyChallenge({
          challengeId: uuid(body.challengeId, "challengeId"),
          email: boundedString(body.email, "email", 3, 254),
          code: boundedString(body.code, "code", 6, 6),
          purpose: "reauthenticate",
          platform: principal.installation.platform,
          clientInstallationId: principal.installation.id,
          exchangeIdempotencyKey: idempotencyKey,
        });
        sendJson(response, 200, await this.service.reauthenticateEmailIdentity(
          principal,
          identity,
          { scope, idempotencyKey },
        ));
        return;
      }

      if (url.pathname === "/v2/account/identities/email/challenges"
          && request.method === "POST") {
        this.requireIdentityManagement();
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const result = await emailOtp.requestChallenge({
          email: boundedString(body.email, "email", 3, 254),
          purpose: "link_identity",
          platform: principal.installation.platform,
          source: this.sourceKey(request),
          clientInstallationId: principal.installation.id,
        });
        sendJson(response, 202, { challenge: result });
        return;
      }

      if (url.pathname === "/v2/account/identities/email" && request.method === "POST") {
        this.requireIdentityManagement();
        const emailOtp = this.requireEmailOtp();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const idempotencyKey = uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key");
        const identity = await emailOtp.verifyChallenge({
          challengeId: uuid(body.challengeId, "challengeId"),
          email: boundedString(body.email, "email", 3, 254),
          code: boundedString(body.code, "code", 6, 6),
          purpose: "link_identity",
          platform: principal.installation.platform,
          clientInstallationId: principal.installation.id,
          exchangeIdempotencyKey: idempotencyKey,
        });
        const linked = await this.service.linkEmailIdentity(principal, identity, {
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey,
        });
        sendJson(response, 200, { identity: linked });
        return;
      }

      if (url.pathname === "/v2/account/identities/google" && request.method === "POST"
          && this.googleAuthEnabled()) {
        this.requireIdentityManagement();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const linked = await this.service.linkGoogleIdentity(principal, {
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { identity: linked });
        return;
      }

      const identityMatch = /^\/v2\/account\/identities\/([0-9a-f-]{36})$/i.exec(url.pathname);
      if (identityMatch && request.method === "DELETE") {
        this.requireIdentityManagement();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        sendJson(response, 200, await this.service.unlinkIdentity(principal, {
          identityId: uuid(identityMatch[1], "identityId"),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        }));
        return;
      }

      if (url.pathname === "/v2/installations" && request.method === "GET") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, { items: await control.listInstallations(principal) });
        return;
      }

      if (url.pathname === "/v2/installations/current" && request.method === "DELETE") {
        const control = this.requireControl();
        await control.revokeCurrentPhoneInstallation(
          firstHeader(request, "authorization"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      const installationMatch = /^\/v2\/installations\/([0-9a-f-]{36})$/i.exec(url.pathname);
      if (installationMatch && request.method === "DELETE") {
        this.requireIdentityManagement();
        const control = this.requireAccountSecurity();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        await control.revokeManagedPhoneInstallation(
          principal,
          uuid(installationMatch[1], "installationId"),
          boundedString(body.grant, "grant", 1, 256),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/devices"
          && request.method === "GET"
          && this.options.multiDeviceEnabled) {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, {
          items: await control.listDevices(principal),
          maxOwnedDevices: 3,
        });
        return;
      }

      const deviceMatch = /^\/v2\/devices\/([^/]+)$/.exec(url.pathname);
      if (deviceMatch && request.method === "GET" && this.options.multiDeviceEnabled) {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, {
          device: await control.getDevice(principal, decodedPathSegment(deviceMatch[1], "deviceId")),
        });
        return;
      }

      const defaultDeviceMatch = /^\/v2\/devices\/([^/]+)\/select-default$/.exec(url.pathname);
      if (defaultDeviceMatch && request.method === "POST" && this.options.multiDeviceEnabled) {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const device = await control.selectDefaultDevice(
          principal,
          decodedPathSegment(defaultDeviceMatch[1], "deviceId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendJson(response, 200, { device });
        return;
      }

      const sharesMatch = /^\/v2\/devices\/([^/]+)\/shares$/.exec(url.pathname);
      if (sharesMatch && request.method === "GET") {
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, await sharing.listShares(
          principal,
          decodedPathSegment(sharesMatch[1], "deviceId"),
        ));
        return;
      }

      const invitationCollectionMatch = /^\/v2\/devices\/([^/]+)\/share-invitations$/
        .exec(url.pathname);
      if (invitationCollectionMatch && request.method === "POST") {
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const invitation = await sharing.createInvitation(principal, {
          deviceId: decodedPathSegment(invitationCollectionMatch[1], "deviceId"),
          email: boundedString(body.email, "email", 3, 254),
          grant: boundedString(body.grant, "grant", 1, 256),
          acknowledgedWholeDeviceAccess: exactBoolean(
            body.acknowledgedWholeDeviceAccess,
            "acknowledgedWholeDeviceAccess",
          ),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 202, { invitation });
        return;
      }

      const invitationMatch = /^\/v2\/devices\/([^/]+)\/share-invitations\/([0-9a-f-]{36})$/i
        .exec(url.pathname);
      if (invitationMatch && request.method === "DELETE") {
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        await sharing.cancelInvitation(
          principal,
          decodedPathSegment(invitationMatch[1], "deviceId"),
          uuid(invitationMatch[2], "invitationId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      const shareGrantMatch = /^\/v2\/devices\/([^/]+)\/shares\/([0-9a-f-]{36})$/i
        .exec(url.pathname);
      if (shareGrantMatch && request.method === "DELETE") {
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        await sharing.revokeGrant(
          principal,
          decodedPathSegment(shareGrantMatch[1], "deviceId"),
          uuid(shareGrantMatch[2], "grantId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      const acceptInvitationMatch = /^\/v2\/share-invitations\/(hsi_[A-Za-z0-9_-]{43})\/accept$/
        .exec(url.pathname);
      if (acceptInvitationMatch && request.method === "POST") {
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const device = await sharing.acceptInvitation(
          principal,
          acceptInvitationMatch[1],
          exactBoolean(body.acknowledgedWholeDeviceAccess, "acknowledgedWholeDeviceAccess"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        sendJson(response, 200, { device });
        return;
      }

      const leaveMatch = /^\/v2\/devices\/([^/]+)\/leave$/.exec(url.pathname);
      if (leaveMatch && request.method === "POST") {
        const sharing = this.requireSharing();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        await sharing.leaveDevice(
          principal,
          decodedPathSegment(leaveMatch[1], "deviceId"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (deviceMatch && request.method === "DELETE" && this.options.multiDeviceEnabled) {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        await control.unbindDevice(principal, {
          deviceId: decodedPathSegment(deviceMatch[1], "deviceId"),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/connector-binding" && request.method === "GET") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, await control.getBinding(principal));
        return;
      }

      if (url.pathname === "/v2/connector-binding" && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const binding = await control.createPendingBinding(principal, {
          desktopInstallationId: uuid(body.desktopInstallationId, "desktopInstallationId"),
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          connectorPublicKey: boundedString(body.connectorPublicKey, "connectorPublicKey", 43, 43),
          keyAlgorithm: boundedString(body.keyAlgorithm, "keyAlgorithm", 1, 32),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 201, binding);
        return;
      }

      if (url.pathname === "/v2/connector-binding/confirm" && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const binding = await control.confirmPendingBinding(principal, {
          bindingId: uuid(body.bindingId, "bindingId"),
          generation: boundedInteger(body.generation, "generation", 1, 2_147_483_647),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { state: "bound", binding });
        return;
      }

      if (url.pathname === "/v2/connector-binding/replacement-requests"
          && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const replacement = await control.createReplacementRequest(principal, {
          desktopInstallationId: uuid(body.desktopInstallationId, "desktopInstallationId"),
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          connectorPublicKey: boundedString(body.connectorPublicKey, "connectorPublicKey", 43, 43),
          keyAlgorithm: boundedString(body.keyAlgorithm, "keyAlgorithm", 1, 32),
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 201, replacement);
        return;
      }

      const replacementConfirmation = /^\/v2\/connector-binding\/replacement-requests\/([0-9a-f-]{36})\/confirm$/i
        .exec(url.pathname);
      if (replacementConfirmation && request.method === "POST") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const binding = await control.confirmReplacementRequest(principal, {
          requestId: uuid(replacementConfirmation[1], "requestId"),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { state: "bound", binding });
        return;
      }

      if (url.pathname === "/v2/connector-binding" && request.method === "DELETE") {
        const control = this.requireControl();
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        await control.unbindConnector(principal, {
          grant: boundedString(body.grant, "grant", 1, 256),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/auth/google/exchange" && request.method === "POST"
          && this.googleAuthEnabled()) {
        this.exchangeLimiter.requireAllowance(this.sourceKey(request));
        const body = await readJsonObject(request);
        const platform = accountPlatform(body.platform);
        const result = await this.service.exchangeGoogleProof({
          platform,
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          clientInstallationId: uuid(body.clientInstallationId, "clientInstallationId"),
          displayName: boundedDisplayString(body.displayName, "displayName", 128),
          appVersion: boundedDisplayString(body.appVersion, "appVersion", 64),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, result);
        return;
      }

      if (url.pathname === "/v2/auth/refresh" && request.method === "POST") {
        this.refreshLimiter.requireAllowance(this.sourceKey(request));
        const body = await readJsonObject(request);
        const result = await this.service.refresh({
          refreshToken: boundedString(body.refreshToken, "refreshToken", 1, 256),
          clientInstallationId: uuid(body.clientInstallationId, "clientInstallationId"),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, { session: result });
        return;
      }

      if (url.pathname === "/v2/account" && request.method === "GET") {
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        sendJson(response, 200, {
          account: principal.account,
          installation: principal.installation,
          session: { authenticated: true, recentReauthentication: false },
        });
        return;
      }

      if (url.pathname === "/v2/account" && request.method === "DELETE"
          && this.options.accountDeletionEnabled) {
        const body = await readJsonObject(request);
        await this.service.requestAccountDeletion(
          firstHeader(request, "authorization"),
          boundedString(body.grant, "grant", 1, 256),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
          exactBoolean(
            body.acknowledgedPermanentCloudDeletion,
            "acknowledgedPermanentCloudDeletion",
          ),
        );
        sendNoContent(response);
        return;
      }

      if (url.pathname === "/v2/auth/reauth/google" && request.method === "POST"
          && this.googleAuthEnabled()) {
        const principal = await this.service.authenticate(firstHeader(request, "authorization"));
        const body = await readJsonObject(request);
        const result = await this.service.reauthenticateGoogle(principal, {
          idToken: boundedString(body.idToken, "idToken", 1, 16_384),
          nonce: boundedString(body.nonce, "nonce", 16, 256),
          scope: reauthenticationScope(body.scope),
          idempotencyKey: uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        });
        sendJson(response, 200, result);
        return;
      }

      if (url.pathname === "/v2/auth/revoke-all" && request.method === "POST") {
        const body = await readJsonObject(request);
        await this.service.revokeAllSessions(
          firstHeader(request, "authorization"),
          boundedString(body.grant, "grant", 1, 256),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      if (url.pathname === "/v2/auth/sign-out" && request.method === "POST") {
        await this.service.signOut(
          firstHeader(request, "authorization"),
          uuid(firstHeader(request, "idempotency-key"), "Idempotency-Key"),
        );
        response.writeHead(204, { "cache-control": "no-store" });
        response.end();
        return;
      }

      throw new AccountModeError(404, "HR-ACCOUNT-004", "The account endpoint was not found.", false, "none");
    } catch (error) {
      const mapped = error instanceof AccountModeError ? error : accountErrors.unavailable();
      if (!(error instanceof AccountModeError)) {
        console.error("Account request failed", {
          correlationId,
          errorType: error instanceof Error ? error.name : "unknown",
        });
      }
      sendJson(response, mapped.status, {
        error: {
          code: mapped.code,
          message: mapped.message,
          retryable: mapped.retryable,
          recoveryAction: mapped.recoveryAction,
          correlationId,
        },
      }, mapped.code === "HR-AUTH-007" ? { "retry-after": "60" } : {});
    }
  }

  private sourceKey(request: IncomingMessage): string {
    const peer = request.socket.remoteAddress ?? "unknown";
    if (!this.options.trustLoopbackProxy || !isLoopback(peer)) return peer;
    const forwarded = firstHeader(request, "x-forwarded-for")?.split(",", 1)[0]?.trim();
    return forwarded && isIP(forwarded) !== 0 ? forwarded : peer;
  }

  private requireControl(): AccountControlService {
    if (!this.options.controlEnabled || !this.options.controlService) {
      throw accountErrors.bindingFeatureDisabled();
    }
    return this.options.controlService;
  }

  private requireAccountSecurity(): AccountControlService {
    if (!this.options.controlService) throw accountErrors.identityFeatureDisabled();
    return this.options.controlService;
  }

  private requireEmailOtp(): EmailOtpService {
    if (!this.options.emailOtpEnabled || !this.options.emailOtpService) {
      throw accountErrors.emailFeatureDisabled();
    }
    return this.options.emailOtpService;
  }

  private requireIdentityManagement(): void {
    if (!this.options.identityManagementEnabled) throw accountErrors.identityFeatureDisabled();
  }

  private requireSharing(): AccountSharingService {
    if (!this.options.sharingEnabled || !this.options.sharingService) {
      throw accountErrors.sharingFeatureDisabled();
    }
    return this.options.sharingService;
  }

  private requireWebSession(): WebSessionSecurity {
    if (!this.options.webSessionEnabled || !this.options.webSessionSecurity) {
      throw accountErrors.webSessionFeatureDisabled();
    }
    return this.options.webSessionSecurity;
  }

  private googleAuthEnabled(): boolean {
    return this.options.googleAuthEnabled ?? false;
  }
}

function capabilities(
  enabled: boolean,
  controlEnabled: boolean,
  emailOtpEnabled: boolean,
  googleAuthEnabled: boolean,
  identityManagementEnabled: boolean,
  accountDeletionEnabled: boolean,
  webAccountCenterEnabled: boolean,
  webSessionEnabled: boolean,
  multiDeviceEnabled: boolean,
  sharingEnabled: boolean,
  desktopManagedInstallEnabled: boolean,
  serverRelease?: ServerReleaseManifest,
): AccountCapabilities {
  return {
    version: 1,
    accountAuth: {
      enabled,
      providers: [
        ...(googleAuthEnabled ? ["google" as const] : []),
        ...(emailOtpEnabled ? ["email_otp" as const] : []),
      ],
      android: true,
      macos: true,
      identityManagement: enabled && identityManagementEnabled,
      ...(enabled && accountDeletionEnabled ? { accountDeletion: true as const } : {}),
      webAccountCenter: enabled && webAccountCenterEnabled,
      ...(enabled && webSessionEnabled ? { webSessions: true as const } : {}),
    },
    binding: {
      enabled: enabled && controlEnabled,
      replacement: enabled && controlEnabled,
      maxActiveConnectorsPerAccount: multiDeviceEnabled ? 3 : 1,
      ...(enabled && controlEnabled && multiDeviceEnabled
        ? { supportsDeviceSelection: true as const }
        : {}),
      ...(enabled && controlEnabled && multiDeviceEnabled && sharingEnabled
        ? {
            supportsDeviceSharing: true as const,
            maxSharedDevices: 10 as const,
            maxGranteesPerDevice: 5 as const,
          }
        : {}),
    },
    legacy: {
      appTokenAccepted: true,
      connectorTokenAccepted: true,
    },
    ...(enabled && controlEnabled && desktopManagedInstallEnabled ? {
      desktopBootstrap: {
        runtimeContract: "hermes-serve-v1" as const,
      },
    } : {}),
    ...(serverRelease ? {
      server: {
        version: serverRelease.serverVersion,
        protocolVersions: serverRelease.protocolVersions,
        minimumClients: serverRelease.minimumClients,
      },
    } : {}),
  };
}

async function readJsonObject(request: IncomingMessage): Promise<Record<string, unknown>> {
  const contentType = firstHeader(request, "content-type")?.split(";", 1)[0]?.trim().toLowerCase();
  if (contentType !== "application/json") {
    throw accountErrors.invalidRequest("Content-Type must be application/json.");
  }
  const chunks: Buffer[] = [];
  let size = 0;
  try {
    for await (const chunk of request) {
      const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      size += bytes.length;
      if (size > MAX_ACCOUNT_BODY_BYTES) {
        throw accountErrors.invalidRequest("The account request body is too large.");
      }
      chunks.push(bytes);
    }
    const parsed: unknown = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    if (!isRecord(parsed)) throw new Error("not_an_object");
    return parsed;
  } catch (error) {
    if (error instanceof AccountModeError) throw error;
    throw accountErrors.invalidRequest("The account request body must be valid JSON.");
  }
}

function accountPlatform(value: unknown): AccountPlatform {
  if (value === "android" || value === "macos") return value;
  throw accountErrors.invalidRequest("platform must be android or macos.");
}

function reauthenticationScope(value: unknown): ReauthenticationScope {
  if (value === "connector.replace" || value === "connector.unbind"
      || value === "account.revoke_all" || value === "account.identity.link"
      || value === "account.identity.unlink" || value === "account.installation.revoke"
      || value === "account.delete" || value === "device.share") {
    return value;
  }
  throw accountErrors.invalidRequest("scope is not a supported reauthentication operation.");
}

function normalizedEmailInput(value: unknown): string {
  const email = boundedString(value, "email", 3, 254);
  try {
    return normalizeEmailAddress(email);
  } catch {
    throw accountErrors.invalidRequest("email must be a valid mailbox address.");
  }
}

function boundedString(
  value: unknown,
  field: string,
  minimum: number,
  maximum: number,
): string {
  if (typeof value !== "string" || value.length < minimum || value.length > maximum) {
    throw accountErrors.invalidRequest(`${field} must contain ${minimum}-${maximum} characters.`);
  }
  return value;
}

function boundedDisplayString(value: unknown, field: string, maximum: number): string {
  const result = boundedString(value, field, 1, maximum);
  if (result.trim().length === 0 || /[\u0000-\u001f\u007f]/.test(result)) {
    throw accountErrors.invalidRequest(`${field} contains unsupported characters.`);
  }
  return result;
}

function decodedPathSegment(value: string, field: string): string {
  try {
    return boundedString(decodeURIComponent(value), field, 1, 128);
  } catch (error) {
    if (error instanceof AccountModeError) throw error;
    throw accountErrors.invalidRequest(`${field} is not valid URL encoding.`);
  }
}

function uuid(value: unknown, field: string): string {
  const result = boundedString(value, field, 36, 36);
  if (!UUID_PATTERN.test(result)) throw accountErrors.invalidRequest(`${field} must be a UUID.`);
  return result.toLowerCase();
}

function boundedInteger(value: unknown, field: string, minimum: number, maximum: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < minimum || (value as number) > maximum) {
    throw accountErrors.invalidRequest(`${field} must be an integer between ${minimum} and ${maximum}.`);
  }
  return value as number;
}

function exactBoolean(value: unknown, field: string): boolean {
  if (typeof value !== "boolean") {
    throw accountErrors.invalidRequest(`${field} must be a boolean.`);
  }
  return value;
}

function optionalWebDisplayName(value: unknown): string {
  return value === undefined ? "Web browser" : boundedDisplayString(value, "displayName", 128);
}

function publicWebSession(result: import("./account-service.js").AccountSessionResponse): unknown {
  return {
    account: result.account,
    installation: result.installation,
    session: {
      authenticated: true,
      accessExpiresAt: result.session.accessExpiresAt,
      refreshExpiresAt: result.session.refreshExpiresAt,
    },
  };
}

function firstHeader(request: IncomingMessage, name: string): string | undefined {
  const value = request.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isLoopback(address: string): boolean {
  return address === "::1" || address.startsWith("127.") || address.startsWith("::ffff:127.");
}

function sendJson(
  response: ServerResponse,
  status: number,
  value: unknown,
  extraHeaders: Record<string, string | string[]> = {},
): void {
  if (response.writableEnded) return;
  response.writeHead(status, {
    "content-type": "application/json",
    "cache-control": "no-store",
    ...extraHeaders,
  });
  response.end(JSON.stringify(value));
}

function sendNoContent(
  response: ServerResponse,
  extraHeaders: Record<string, string | string[]> = {},
): void {
  response.writeHead(204, { "cache-control": "no-store", ...extraHeaders });
  response.end();
}

function sendAccountWebShell(response: ServerResponse, googleAuthEnabled: boolean): void {
  response.writeHead(200, {
    "content-type": "text/html; charset=utf-8",
    "cache-control": "no-store",
    "content-security-policy": googleAuthEnabled
      ? "default-src 'none'; script-src 'self' https://accounts.google.com/gsi/client; style-src 'self' https://accounts.google.com/gsi/style; connect-src 'self' https://accounts.google.com/gsi/; frame-src https://accounts.google.com/gsi/; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
      : "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'",
    "referrer-policy": "no-referrer",
    "cross-origin-opener-policy": googleAuthEnabled ? "same-origin-allow-popups" : "same-origin",
    "x-content-type-options": "nosniff",
    "x-frame-options": "DENY",
    "permissions-policy": "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
  });
  response.end(ACCOUNT_WEB_SHELL);
}

function sendAccountWebAsset(response: ServerResponse, contentType: string, body: string): void {
  response.writeHead(200, {
    "content-type": contentType,
    "cache-control": "no-store",
    "content-security-policy": "default-src 'none'",
    "referrer-policy": "no-referrer",
    "x-content-type-options": "nosniff",
  });
  response.end(body);
}

class FixedWindowLimiter {
  private readonly entries = new Map<string, { count: number; resetAt: number }>();

  constructor(
    private readonly maximum: number,
    private readonly windowMs: number,
  ) {}

  requireAllowance(key: string): void {
    const now = Date.now();
    const current = this.entries.get(key);
    if (!current || current.resetAt <= now) {
      this.compact(now);
      if (!this.entries.has(key) && this.entries.size >= 10_000) {
        throw accountErrors.rateLimited();
      }
      this.entries.set(key, { count: 1, resetAt: now + this.windowMs });
      return;
    }
    current.count += 1;
    if (current.count > this.maximum) throw accountErrors.rateLimited();
  }

  private compact(now: number): void {
    for (const [key, entry] of this.entries) {
      if (entry.resetAt <= now) this.entries.delete(key);
    }
  }
}
