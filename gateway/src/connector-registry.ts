export interface ConnectorRegistry<T> {
  readonly legacyCount: number;
  legacyDeviceIds(): IterableIterator<string>;
  getLegacy(deviceId: string): T | undefined;
  setLegacy(deviceId: string, connector: T): void;
  deleteLegacyIfCurrent(deviceId: string, connector: T): boolean;
  getAccount(bindingId: string): T | undefined;
  replaceAccount(bindingId: string, connector: T): T | undefined;
  deleteAccountIfCurrent(bindingId: string, connector: T): boolean;
  getByRoutingKey(routingKey: string): T | undefined;
}

export class InMemoryConnectorRegistry<T> implements ConnectorRegistry<T> {
  private readonly legacyConnectors = new Map<string, T>();
  private readonly accountConnectors = new Map<string, T>();

  get legacyCount(): number {
    return this.legacyConnectors.size;
  }

  legacyDeviceIds(): IterableIterator<string> {
    return this.legacyConnectors.keys();
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
    return this.accountConnectors.get(bindingId);
  }

  replaceAccount(bindingId: string, connector: T): T | undefined {
    const previous = this.accountConnectors.get(bindingId);
    this.accountConnectors.set(bindingId, connector);
    return previous;
  }

  deleteAccountIfCurrent(bindingId: string, connector: T): boolean {
    if (this.accountConnectors.get(bindingId) !== connector) return false;
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
