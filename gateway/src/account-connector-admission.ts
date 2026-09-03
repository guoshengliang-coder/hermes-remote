export class AccountConnectorAdmission {
  private readonly countsByIp = new Map<string, number>();
  private total = 0;

  constructor(
    private readonly maxTotal: number,
    private readonly maxPerIp: number,
  ) {}

  atCapacity(sourceIp: string): boolean {
    return this.total >= this.maxTotal
      || (this.countsByIp.get(sourceIp) ?? 0) >= this.maxPerIp;
  }

  acquire(sourceIp: string): { release(): void } {
    this.total += 1;
    this.countsByIp.set(sourceIp, (this.countsByIp.get(sourceIp) ?? 0) + 1);
    let active = true;
    return {
      release: () => {
        if (!active) return;
        active = false;
        this.total -= 1;
        const next = (this.countsByIp.get(sourceIp) ?? 1) - 1;
        if (next <= 0) this.countsByIp.delete(sourceIp);
        else this.countsByIp.set(sourceIp, next);
      },
    };
  }
}
