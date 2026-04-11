import type { GraphNode } from '../../types'

interface PixelBuildingProps {
  node: GraphNode
  isSelected: boolean
  onClick: () => void
  hasCatOnRoof?: boolean
}

export function PixelBuilding({ node, isSelected, onClick, hasCatOnRoof }: PixelBuildingProps) {
  const statusClass = node.status.toLowerCase()
  const errorCount = node.issues.filter(i => i.severity === 'ERROR').length
  const warnCount = node.issues.filter(i => i.severity === 'WARNING').length

  // Generate consistent building aesthetics from string hash
  const hash = node.id.split('').reduce((a, b) => {
    a = (a << 5) - a + b.charCodeAt(0)
    return a & a
  }, 0)

  // Determine building dimensions based on complexity
  const width = Math.min(Math.max(node.complexity * 3 + 16, 24), 80)
  const height = Math.min(Math.max(node.complexity * 4 + 20, 30), 120)

  const numWindows = Math.floor((width * height) / 120)

  const isError = node.status === 'ERROR'
  const isWarning = node.status === 'WARNING'

  return (
    <div
      className={`pixel-building ${isSelected ? 'selected' : ''}`}
      data-node-id={node.id}
      style={{ width, pointerEvents: 'auto' }}
    >
      {/* Dynamic event: sleeping cat on the roof */}
      {hasCatOnRoof && (
        <div className="cat-sleeping" />
      )}

      {/* Badges/Labels */}
      {errorCount > 0 && <div className="building-badge error">{errorCount}</div>}
      {warnCount > 0 && errorCount === 0 && <div className="building-badge warning">{warnCount}</div>}

      {isWarning && (
        <div className="building-crane">
          <div className="crane-pole" />
          <div className="crane-arm" />
          <div className="crane-hook" />
        </div>
      )}

      {/* Roof */}
      <div className={`building-roof ${statusClass} ${isError ? 'collapsed' : ''}`}>
        {!isError && <div className={`building-flag ${statusClass}`} />}
        {isError && <div className="cat-sleeping" style={{ bottom: 8, left: '40%' }} />}
      </div>

      {/* Body */}
      <div
        className={`building-body ${statusClass}`}
        style={{ height, width: '100%', boxSizing: 'border-box' }}
      >
        {isWarning && <div className="scaffolding" />}

        {/* Normal Cracks */}
        {(isError || isWarning) && !isError && (
          <div className="building-cracks">
            <div className="crack-line" />
            <div className="crack-line" />
          </div>
        )}

        {/* DEVASTATED ERROR EFFECTS */}
        {isError && (
          <>
            <div className="building-cracks">
              <div className="crack-line huge" />
              <div className="crack-line huge" />
              <div className="crack-line huge" />
            </div>

            <div className="exposed-girder" />

            <div className="smoke-particles">
              <div className="smoke-particle" />
              <div className="smoke-particle" />
              <div className="smoke-particle" />
            </div>

            <div className="pixel-fire">
              <div className="flame" />
              <div className="flame" />
              <div className="flame" />
            </div>

            <div className="hazard-tape" />
          </>
        )}

        {/* Windows */}
        <div
          className="building-windows"
          style={{
            gridTemplateColumns: `repeat(auto-fill, minmax(7px, 1fr))`,
            maxHeight: height - 12,
            overflow: 'hidden',
          }}
        >
          {Array.from({ length: numWindows }).map((_, i) => {
            const isBroken = isError && (hash + i) % 3 === 0
            const isDark = (hash * i) % 2 === 0
            return (
              <div
                key={i}
                className={`building-window ${isBroken ? 'broken' : isDark ? 'dark' : 'lit'}`}
              />
            )
          })}
        </div>

        {/* Door */}
        <div className={`building-door ${statusClass}`} />
      </div>

      {/* Ground Label */}
      <div className="building-label" title={node.name}>
        {node.name.length > 8 ? node.name.slice(0, 7) + '…' : node.name}
      </div>
    </div>
  )
}
