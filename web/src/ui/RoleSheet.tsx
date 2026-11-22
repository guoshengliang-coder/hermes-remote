import { Sheet } from "./Sheet";
import { CheckIcon } from "./icons";

/** Same avatar/name rows for switching and filtering; a filter stays open after a toggle. */
export function RoleSheet({ names, label, title, closeLabel, selected, multiple, allLabel, onAll, onToggle, onClose }:
  { names: string[]; label: (name: string) => string; title: string; closeLabel: string;
    selected: (name: string) => boolean; multiple?: boolean; allLabel?: string; onAll?: () => void;
    onToggle: (name: string) => void; onClose: () => void }) {
  return <Sheet title={title} closeLabel={closeLabel} onClose={onClose}>
    {onAll && <button type="button" class="sheet-action" onClick={onAll}>{allLabel}</button>}
    {names.map(name => <button type="button" key={name} class="sheet-action role-option"
      role={multiple ? "checkbox" : undefined} aria-checked={multiple ? selected(name) : undefined}
      aria-pressed={multiple ? undefined : selected(name)} onClick={() => onToggle(name)}>
      <span class="profile-initial" aria-hidden="true">{Array.from(label(name))[0]}</span>
      <span class="role-option-label">{label(name)}</span>
      <span class="role-option-check">{selected(name) ? <CheckIcon size={18}/> : null}</span>
    </button>)}
  </Sheet>;
}
