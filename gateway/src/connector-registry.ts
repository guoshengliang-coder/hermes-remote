export interface ConnectorRegistry<T> {
  readonly legacyCount: number;
  readonly accountCount: number;
  legacyDeviceIds(): IterableIterator<string>;
  accountConnections(): IterableIterator<AccountConnectorConnection<T>>;
  getLegacy(deviceId: string): T | undefined;
  setLegacy(deviceId: string, connector: T): void;
  deleteLegacyIfCurrent(deviceId: string, connector: T): boolean;
  getAccount(bindingId: string): T | undefined;
  replaceAccount(bindingId: string, connector: T): T | undefined;
  deleteAccountIfCurrent(bindingId: string, connector: T): boolean;
  getByRoutingKey(routingKey: string): T | undefined;
}

export interface AccountConnectorConnection<T> {
  bindingId: string;
  connector: T;
  connectedAt: string;
}

export class InMemoryConnectorRegistry<T> implements ConnectorRegistry<T> {
  private readonly legacyConnectors = new Map<string, T>();
  private readonly accountConnectors = new Map<string, AccountConnectorConnection<T>>();

  get legacyCount(): number {
    return this.legacyConnectors.size;
  }

  get accountCount(): number {
    return this.accountConnectors.size;
  }

  legacyDeviceIds(): IterableIterator<string> {
    return this.legacyConnectors.keys();
  }

  accountConnections(): IterableIterator<AccountConnectorConnection<T>> {
    return this.accountConnectors.values();
  }

  getLegacy(deviceId: string): T | undefined {
    return this.legacyConnectors.get(deviceId);
  }

  setLegacy(deviceId: string, connector: T): void {
    this.legacyConnectors.set(deviceId, connector);
  }

  deleteLegacyIfCurrent(deviceId: string, connector: T): boolean {
    if (this.legacyConnectors.get(deviceId) !== connector) return false;
    this.legacyConnectors.delete(deviceId);
    return true;
  }

  getAccount(bindingId: string): T | undefined {
    return this.accountConnectors.get(bindingId)?.connector;
  }

  replaceAccount(bindingId: string, connector: T): T | undefined {
    const previous = this.accountConnectors.get(bindingId)?.connector;
    this.accountConnectors.set(bindingId, {
      bindingId,
      connector,
      connectedAt: new Date().toISOString(),
    });
    return previous;
  }

  deleteAccountIfCurrent(bindingId: string, connector: T): boolean {
    if (this.accountConnectors.get(bindingId)?.connector !== connector) return false;
    this.accountConnectors.delete(bindingId);
    return true;
  }

  getByRoutingKey(routingKey: string): T | undefined {
    if (routingKey.startsWith("legacy:")) {
      return this.getLegacy(routingKey.slice("legacy:".length));
    }
    if (routingKey.startsWith("account:")) {
      return this.getAccount(routingKey.slice("account:".length));
    }
    return undefined;
  }
}

export function legacyRoutingKey(deviceId: string): string {
  return `legacy:${deviceId}`;
}

export function accountRoutingKey(bindingId: string): string {
  return `account:${bindingId}`;
}
