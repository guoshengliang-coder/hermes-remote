/** null means all, while [] intentionally means no rows. Hidden roles are never eligible. */
export function roleIncluded(selection: readonly string[] | null, visible: readonly string[], role: string | null) {
  return role !== null && visible.includes(role) && (selection === null || selection.includes(role));
}
export function toggleRole(selection: readonly string[] | null, visible: readonly string[], role: string): string[] {
  const current = (selection ?? visible).filter(name => visible.includes(name));
  return current.includes(role) ? current.filter(name => name !== role) : [...current, role];
}
