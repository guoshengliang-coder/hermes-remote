// Stroke icons (DESIGN §4.1: one outline style, 20px glyphs inside 44px targets).

interface IconProps {
  size?: number;
}

function Svg({ size = 20, children }: IconProps & { children: preact.ComponentChildren }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      stroke-width="1.8"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

export const SearchIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="11" cy="11" r="6.5" />
    <path d="M20 20l-4.2-4.2" />
  </Svg>
);

export const MoreIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="5" cy="12" r="1" />
    <circle cx="12" cy="12" r="1" />
    <circle cx="19" cy="12" r="1" />
  </Svg>
);

export const BackIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M15 5l-7 7 7 7" />
  </Svg>
);

export const CloseIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6 6l12 12M18 6L6 18" />
  </Svg>
);

export const PlusIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 5v14M5 12h14" />
  </Svg>
);

export const SendIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 19V5M6 11l6-6 6 6" />
  </Svg>
);

export const StopIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="7" y="7" width="10" height="10" rx="2" />
  </Svg>
);

export const AttachIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20 11.5l-7.8 7.8a5 5 0 01-7.1-7.1l8.5-8.5a3.3 3.3 0 014.7 4.7l-8.5 8.5a1.7 1.7 0 01-2.4-2.4l7.8-7.8" />
  </Svg>
);

export const FileIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8z" />
    <path d="M14 3v5h5" />
  </Svg>
);

export const ChevronIcon = (p: IconProps & { open?: boolean }) => (
  <Svg size={p.size ?? 16}>
    <path d={p.open ? "M6 9l6 6 6-6" : "M9 6l6 6-6 6"} />
  </Svg>
);

export const MacIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3" y="4" width="18" height="12" rx="2" />
    <path d="M8 20h8M12 16v4" />
  </Svg>
);

export const MoonIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20.5 14.1A8.5 8.5 0 019.9 3.5 8.5 8.5 0 1020.5 14.1z" />
  </Svg>
);

export const SunIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M2 12h2m16 0h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Svg>
);

export const CubeIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 2.8l8 4.6v9.2l-8 4.6-8-4.6V7.4zM4 7.4l8 4.6 8-4.6M12 12v9.2" />
  </Svg>
);

export const SettingsIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="3" />
    <path d="M10 2.8h4l.6 2.1 1.8 1 2.1-.5 2 3.5-1.5 1.6v2.1l1.5 1.6-2 3.5-2.1-.5-1.8 1-.6 2.1h-4l-.6-2.1-1.8-1-2.1.5-2-3.5 1.5-1.6v-2.1L3.5 8.9l2-3.5 2.1.5 1.8-1z" />
  </Svg>
);

export const CopyIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="8" y="8" width="12" height="12" rx="2.5" />
    <path d="M16 8V6a2 2 0 00-2-2H6a2 2 0 00-2 2v8a2 2 0 002 2h2" />
  </Svg>
);

export const CheckIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M5 12.5l4.5 4.5L19 7.5" />
  </Svg>
);

export const FolderIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M3.5 7.5a2 2 0 012-2h4l2 2.5h7a2 2 0 012 2v7.5a2 2 0 01-2 2h-13a2 2 0 01-2-2z" />
  </Svg>
);

export const DownloadIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 4v11M7 10.5l5 5 5-5M5 20h14" />
  </Svg>
);

/**
 * The pinned-session marker (DESIGN §5.2, Android PinIcon.kt): a small filled two-tone push pin —
 * the one exception to the outline style, like on Android. Head in the pinned blue, needle neutral.
 */
export const PinMark = ({ size = 13, label }: { size?: number; label?: string }) => (
  <svg
    class="pin-mark"
    width={size}
    height={size}
    viewBox="0 0 24 24"
    role={label ? "img" : undefined}
    aria-label={label}
    aria-hidden={label ? undefined : "true"}
    focusable="false"
  >
    <path class="pin-needle" d="M11.25 12h1.5L12 21z" />
    <path class="pin-head" d="M8.5 2.5h7V5H14l2.5 7.5h-9L10 5H8.5z" />
  </svg>
);

export const RefreshIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M19.5 12a7.5 7.5 0 11-2.2-5.3M19.5 4.5v4h-4" />
  </Svg>
);

export const SpeakerIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 9.5h3.5L12 5.5v13l-4.5-4H4z" />
    <path d="M15.5 9a4 4 0 010 6M18 6.5a7.5 7.5 0 010 11" />
  </Svg>
);

export const ThumbUpIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M7.5 10.5v9H4.5v-9zM7.5 10.5l3.5-6.5a2 2 0 012 2.2l-.5 3.3h5a2 2 0 012 2.3l-1.2 6.5a2 2 0 01-2 1.7H7.5" />
  </Svg>
);

export const ThumbDownIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M7.5 13.5v-9H4.5v9zM7.5 13.5l3.5 6.5a2 2 0 002-2.2l-.5-3.3h5a2 2 0 002-2.3l-1.2-6.5a2 2 0 00-2-1.7H7.5" />
  </Svg>
);

export const ShareIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 15V4M7.5 8.5L12 4l4.5 4.5M6 12.5v5.5a2 2 0 002 2h8a2 2 0 002-2v-5.5" />
  </Svg>
);

export const ListIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9 7h11M9 12h11M9 17h11M4.5 7h.01M4.5 12h.01M4.5 17h.01" />
  </Svg>
);

export const ArrowDownIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 5v14M6 13l6 6 6-6" />
  </Svg>
);

export const ChevronUpIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6 15l6-6 6 6" />
  </Svg>
);

export const ChevronDownIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M6 9l6 6 6-6" />
  </Svg>
);

export const BranchIcon = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="6.5" cy="5.5" r="2" />
    <circle cx="6.5" cy="18.5" r="2" />
    <circle cx="17.5" cy="7.5" r="2" />
    <path d="M6.5 7.5v9M17.5 9.5c0 4-5.5 3.5-10 7" />
  </Svg>
);

export const PinOutlineIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9 3.5h6V6h-1.3l2.3 7H8l2.3-7H9zM12 13v7.5" />
  </Svg>
);

export const ArchiveIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3.5" y="4.5" width="17" height="4" rx="1" />
    <path d="M5 8.5v9a2 2 0 002 2h10a2 2 0 002-2v-9M10 12.5h4" />
  </Svg>
);

export const ChatIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M5 5.5h14a1.5 1.5 0 011.5 1.5v8.5A1.5 1.5 0 0119 17H10l-4.5 3.5V17H5a1.5 1.5 0 01-1.5-1.5V7A1.5 1.5 0 015 5.5z" />
  </Svg>
);

export const BotIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="4.5" y="8" width="15" height="11" rx="3" />
    <path d="M12 8V4.5M9.5 13h.01M14.5 13h.01M9.5 16.5h5" />
  </Svg>
);

export const StarIcon = ({ size = 20, filled = false }: IconProps & { filled?: boolean }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={filled ? "currentColor" : "none"} stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path d="M12 3.8l2.5 5.2 5.7.8-4.1 4 1 5.7L12 16.8l-5.1 2.7 1-5.7-4.1-4 5.7-.8z" />
  </svg>
);

export const TerminalIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="3.5" y="4.5" width="17" height="15" rx="2" />
    <path d="M7.5 9.5l3 2.5-3 2.5M12.5 15h4" />
  </Svg>
);

export const ArrowUpIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M12 19V5M6 11l6-6 6 6" />
  </Svg>
);

export const CameraIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 8.5a2 2 0 012-2h2l1.5-2h5L16 6.5h2a2 2 0 012 2V17a2 2 0 01-2 2H6a2 2 0 01-2-2z" />
    <circle cx="12" cy="12.5" r="3.5" />
  </Svg>
);

export const ImageIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="4" y="5" width="16" height="14" rx="2" />
    <circle cx="9" cy="10" r="1.5" />
    <path d="M20 16l-4.5-4.5L8 19" />
  </Svg>
);

export const PenIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 20l1-4.5L15.5 5a2.1 2.1 0 013 3L8 18.5 4 20z" />
    <path d="M13.5 7l3 3" />
  </Svg>
);

export const MosaicIcon = (p: IconProps) => (
  <Svg {...p}>
    <rect x="4" y="4" width="16" height="16" rx="2" />
    <path d="M4 12h16M12 4v16" />
    <path d="M8 4v4H4M16 12v4h4M12 8h4V4M8 20v-4H4" />
  </Svg>
);

export const CropIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M7 3v14h14" />
    <path d="M3 7h14v14" />
  </Svg>
);

export const RotateIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M20 11a8 8 0 10-2.3 5.7" />
    <path d="M20 4v7h-7" />
  </Svg>
);

export const UndoIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M9 14L4 9l5-5" />
    <path d="M4 9h10.5a5.5 5.5 0 010 11H11" />
  </Svg>
);

export const RedoIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M15 14l5-5-5-5" />
    <path d="M20 9H9.5a5.5 5.5 0 000 11H13" />
  </Svg>
);

export const EditIcon = PenIcon;

export const TrashIcon = (p: IconProps) => (
  <Svg {...p}>
    <path d="M4 7h16M10 11v6M14 11v6" />
    <path d="M6 7l1 12a2 2 0 002 2h6a2 2 0 002-2l1-12M9 7V4h6v3" />
  </Svg>
);
