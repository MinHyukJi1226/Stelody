const paths = {
  edit: 'm15 5 4 4M4 20l4.7-1 11-11a2.8 2.8 0 0 0-4-4l-11 11L4 20Z',
  delete:
    'M3 6h18M9 6V3h6v3m4 0-1 14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1L5 6m5 4v7m4-7v7',
  up: 'M12 20V4m-6 6 6-6 6 6',
  down: 'M12 4v16m-6-6 6 6 6-6',
}

export function PlaylistActionIcon({ kind }: { kind: keyof typeof paths }) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={paths[kind]} />
    </svg>
  )
}
