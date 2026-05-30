interface ConfidencePillProps {
  confidence?: string
}

export function ConfidencePill({ confidence }: ConfidencePillProps) {
  if (!confidence) return null

  let text = confidence
  let bg = '#6B7280' // default gray
  let fg = '#ffffff'
  let isItalic = false

  switch (confidence) {
    case 'CONFIRMED':
      text = 'CONFIRMED'
      bg = '#1F8B4C'
      break
    case 'LIKELY':
      text = 'LIKELY'
      bg = '#2E5BBA'
      break
    case 'UNCONFIRMED':
      text = 'UNCONFIRMED'
      bg = '#6B7280'
      break
    case 'UNREACHED':
      text = 'NEEDS COVERAGE'
      bg = '#B45309'
      break
    case 'DEMOTED':
      text = 'DEMOTED'
      bg = 'rgba(185, 28, 28, 0.6)'
      isItalic = true
      break
  }

  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        background: bg,
        color: fg,
        fontSize: '7.5px',
        fontWeight: 700,
        padding: '1px 5px',
        borderRadius: '2px',
        letterSpacing: '0.04em',
        fontStyle: isItalic ? 'italic' : 'normal',
        textTransform: 'uppercase',
        lineHeight: 1.2,
        height: '13px',
        boxSizing: 'border-box',
        verticalAlign: 'middle',
      }}
    >
      {text}
    </span>
  )
}
