import type { HealthObservation, LinkHeartbeat, TransportOffer } from "@hermes-remote/protocol";
import type { GatewayPeer } from "./gateway-peer.js";

export interface TransportMember {
  peer: GatewayPeer;
  offer: TransportOffer;
  heartbeat: LinkHeartbeat;
}
interface Session {
  instanceId: string;
  bindingGeneration: number;
  members: Map<TransportOffer["role"], TransportMember>;
  generations: Map<TransportOffer["role"], number>;
  health?: HealthObservation & { receivedAt: number };
  emptySince?: number;
}
/** Physical control replacement never replaces the routable business peer. */
export class AccountTransportSessions {
  private readonly sessions = new Map<string, Session>();
  private readonly retired = new Map<string, number>();
  private readonly bySocket = new WeakMap<GatewayPeer["socket"], TransportMember>();
  constructor(private readonly registerBusiness: (peer: GatewayPeer) => void,
    private readonly unregisterBusiness: (peer: GatewayPeer) => boolean,
    private readonly now = () => performance.now()) {}
  canAttach(bindingId: string, bindingGeneration: number, offer: TransportOffer): boolean {
    if ((this.retired.get(`${bindingId}:${offer.instanceId}`) ?? 0) > this.now()) return false;
    const session = this.sessions.get(bindingId);
    if (session && session.bindingGeneration > bindingGeneration) return false;
    if (offer.role === "control" && (!session || session.instanceId !== offer.instanceId || session.bindingGeneration !== bindingGeneration)) return false;
    return !session || session.instanceId !== offer.instanceId || offer.generation > (session.generations.get(offer.role) ?? 0);
  }
  register(member: TransportMember): boolean {
    const { peer, offer } = member;
    const id = peer.binding!.id;
    if (!this.canAttach(id, peer.binding!.generation, offer)) return false;
    for (const [key, value] of this.sessions) {
      if (value.emptySince !== undefined && this.now() - value.emptySince >= 10 * 60_000) this.sessions.delete(key);
    }
    for (const [key, expires] of this.retired) if (expires <= this.now()) this.retired.delete(key);
    if (this.retired.has(`${id}:${offer.instanceId}`)) return false;
    let session = this.sessions.get(id);
    if (session && session.bindingGeneration !== peer.binding!.generation) {
      this.close(id); session = undefined;
    }
    if (session && session.instanceId !== offer.instanceId) {
      if (offer.role !== "business") return false;
      this.retired.set(`${id}:${session.instanceId}`, this.now() + 10 * 60_000);
      if (this.retired.size > 4096) this.retired.delete(this.retired.keys().next().value!);
      this.close(id); session = undefined;
    }
    if (!session) {
      if (offer.role !== "business") return false;
      if (this.sessions.size >= 4096) return false;
      session = { instanceId: offer.instanceId, bindingGeneration: peer.binding!.generation,
        members: new Map(), generations: new Map() };
      this.sessions.set(id, session);
    }
    if (offer.generation <= (session.generations.get(offer.role) ?? 0)) return false;
    const previous = session.members.get(offer.role);
    session.generations.set(offer.role, offer.generation);
    session.members.set(offer.role, member);
    session.emptySince = undefined;
    this.bySocket.set(peer.socket, member);
    peer.transport = { offer, heartbeat: member.heartbeat, session };
    if (offer.role === "business") this.registerBusiness(peer);
    previous?.peer.socket.close(4409, "lane replaced");
    return true;
  }
  current(member: TransportMember): boolean {
    return this.sessions.get(member.peer.binding!.id)?.members.get(member.offer.role) === member;
  }
  health(member: TransportMember, health: HealthObservation): void {
    if (!this.current(member)) return;
    const session = this.sessions.get(member.peer.binding!.id)!;
    if (session.health && session.health.sequence >= health.sequence) return;
    session.health = { ...health, receivedAt: this.now() };
  }
  remove(member: TransportMember): boolean {
    if (!this.current(member)) return false;
    const id = member.peer.binding!.id;
    const session = this.sessions.get(id)!;
    session.members.delete(member.offer.role);
    if (member.offer.role === "business") this.unregisterBusiness(member.peer);
    if (session.members.size) return false;
    session.emptySince = this.now();
    return true;
  }
  close(id: string): void {
    const session = this.sessions.get(id);
    if (!session) return;
    this.sessions.delete(id);
    for (const member of session.members.values()) {
      member.heartbeat.stop();
      if (member.offer.role === "business") this.unregisterBusiness(member.peer);
      member.peer.socket.close(4409, "device session replaced or revoked");
    }
  }
  revoke(accountId: string, bindingId?: string): void {
    for (const [id, session] of this.sessions) {
      const peer = session.members.values().next().value?.peer;
      if (peer?.accountId === accountId && (!bindingId || id === bindingId)) this.close(id);
    }
  }
  snapshot(bindingId: string, generation: number): ReturnType<typeof connectionSnapshot> {
    const session = this.sessions.get(bindingId);
    if (session?.bindingGeneration !== generation) return undefined;
    const peer = session.members.values().next().value?.peer;
    return peer ? connectionSnapshot(peer, this.now()) : undefined;
  }
  controlFor(socket: GatewayPeer["socket"]): GatewayPeer["socket"] | undefined {
    const member = this.bySocket.get(socket);
    if (!member || member.offer.role !== "business" || !this.current(member)) return undefined;
    const control = this.sessions.get(member.peer.binding!.id)?.members.get("control")?.peer.socket;
    return control?.readyState === 1 ? control : undefined;
  }
}

export function connectionSnapshot(peer: GatewayPeer, now = performance.now()) {
  const transport = peer.transport;
  if (!transport) return undefined;
  const session = transport.session;
  const health = session.health;
  const ageMs = health ? Math.floor(health.ageMs + Math.max(0, now - health.receivedAt)) : 60_000;
  const lane = (role: TransportOffer["role"]) => session.members.get(role)?.heartbeat.health.state ?? "lost";
  return { version: 1, business: lane("business"), control: lane("control"),
    local: health && ageMs < 60_000 ? health.state : "unknown",
    localReason: health?.reason ?? "unavailable", healthAgeMs: ageMs };
}

export type PeerTransport = { offer: TransportOffer; heartbeat: LinkHeartbeat; session: Session };
