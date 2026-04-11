import { useMemo, useCallback, useState, useEffect, useRef } from 'react'
import type { ProjectGraph, GraphNode } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'
import { PixelBuilding } from './PixelBuilding'
import { TransformWrapper, TransformComponent } from 'react-zoom-pan-pinch'
import './pixelcity.css'

interface PixelCityProps {
  graph: ProjectGraph
}

// Deterministic pseudo-random from seed
function seededRandom(seed: number) {
  let s = seed
  return () => {
    s = (s * 16807 + 0) % 2147483647
    return (s - 1) / 2147483646
  }
}

const PERSON_COLORS = ['blue', 'green', 'purple', 'orange', 'red'] as const
const BUILDINGS_PER_BLOCK = 4
const BLOCKS_PER_ROW = 3

export function PixelCity({ graph }: PixelCityProps) {
  const { state, dispatch } = useAppStore()
  const sceneRef = useRef<HTMLDivElement>(null)
  const lastDown = useRef<{ x: number, y: number, time: number } | null>(null)

  // Event Engine State
  const [showClown, setShowClown] = useState(false)
  const [showUfo, setShowUfo] = useState(false)

  // Random event spawner
  useEffect(() => {
    const clownInterval = setInterval(() => {
      if (Math.random() > 0.6) {
        setShowClown(true)
        setTimeout(() => setShowClown(false), 15000) // Clown runs for 15s
      }
    }, 45000)

    const ufoInterval = setInterval(() => {
      if (Math.random() > 0.5) {
        setShowUfo(true)
        setTimeout(() => setShowUfo(false), 35000) // UFO flyover is 35s
      }
    }, 60000)

    // Initial check just for fun
    setTimeout(() => { if (Math.random() > 0.5) setShowUfo(true); setTimeout(() => setShowUfo(false), 35000) }, 5000)

    return () => {
      clearInterval(clownInterval)
      clearInterval(ufoInterval)
    }
  }, [])

  // Sort nodes: errors first, then warnings, then healthy (tallest first within group)
  const sortedNodes = useMemo(() => {
    const statusOrder: Record<string, number> = { ERROR: 0, WARNING: 1, HEALTHY: 2 }
    return [...graph.nodes].sort((a, b) => {
      const sa = statusOrder[a.status] ?? 2
      const sb = statusOrder[b.status] ?? 2
      if (sa !== sb) return sa - sb
      return b.complexity - a.complexity
    })
  }, [graph.nodes])

  // Build Barcelona grid rows
  // Each "row" consists of multiple "blocks" (of 4 buildings) separated by vertical streets
  const gridRows = useMemo(() => {
    const rows: GraphNode[][][] = []
    let currentNodes = [...sortedNodes]

    while (currentNodes.length > 0) {
      const rowBlocks: GraphNode[][] = []
      for (let i = 0; i < BLOCKS_PER_ROW; i++) {
        if (currentNodes.length === 0) break
        rowBlocks.push(currentNodes.splice(0, BUILDINGS_PER_BLOCK))
      }
      rows.push(rowBlocks)
    }
    return rows
  }, [sortedNodes])

  // Generate stars for the sky
  const stars = useMemo(() => {
    const rng = seededRandom(42)
    return Array.from({ length: 40 }, (_, i) => ({
      key: i,
      left: `${rng() * 100}%`,
      top: `${rng() * 50 + 5}px`,
      delay: `${rng() * 3}s`,
      size: rng() > 0.7 ? 3 : 2,
    }))
  }, [])

  // Generate clouds
  const clouds = useMemo(() => {
    const rng = seededRandom(99)
    return Array.from({ length: 6 }, (_, i) => ({
      key: i,
      top: `${rng() * 30 + 10}px`,
      delay: `${rng() * 20}s`,
      width: `${rng() * 16 + 20}px`,
    }))
  }, [])

  // Generate people for the main roads
  const generatePeople = useCallback((rowIdx: number) => {
    const rng = seededRandom(rowIdx * 137 + 7)
    const count = Math.floor(rng() * 4) + 3 // 3-6 people per horizontal road
    return Array.from({ length: count }, (_, i) => ({
      key: `person-${rowIdx}-${i}`,
      color: PERSON_COLORS[Math.floor(rng() * PERSON_COLORS.length)],
      direction: rng() > 0.5 ? 'walk-right' : 'walk-left',
      top: `${rng() * 6 + 4}px`, // vertical position within road
      delay: `${rng() * 8}s`,
      duration: `${rng() * 6 + 10}s`,
    }))
  }, [])

  // Generate decorative elements inside a block
  const generateDecorations = useCallback((blockIdx: number, rowIdx: number, buildingCount: number) => {
    const rng = seededRandom(blockIdx * 53 + rowIdx * 17 + 13)
    const items: Array<{ type: 'tree' | 'lamp' | 'bench' | 'grass'; key: string; hasCat?: boolean }> = []

    // Lamp at the start
    items.push({ type: 'lamp', key: `l-start-${rowIdx}-${blockIdx}` })

    for (let i = 0; i < buildingCount - 1; i++) {
      const roll = rng()
      if (roll < 0.35) {
        items.push({ type: 'tree', key: `t-${rowIdx}-${blockIdx}-${i}` })
      } else if (roll < 0.5) {
        items.push({ type: 'bench', key: `b-${rowIdx}-${blockIdx}-${i}`, hasCat: rng() > 0.7 })
      } else if (roll < 0.65) {
        items.push({ type: 'grass', key: `g-${rowIdx}-${blockIdx}-${i}` })
      }
    }

    if (rng() > 0.5) {
      items.push({ type: 'tree', key: `t-end-${rowIdx}-${blockIdx}` })
    } else {
      items.push({ type: 'lamp', key: `l-end-${rowIdx}-${blockIdx}` })
    }

    return items
  }, [])

  const handleBuildingClick = useCallback((node: GraphNode) => {
    dispatch({ type: 'SELECT_NODE', payload: node })
    bridge.nodeClicked(node.id)
    bridge.requestImpact(node.id)
  }, [dispatch])

  // NATIVE EVENT DELEGATION: Foolproof click detection bypasses React/Library interference
  useEffect(() => {
    const scene = sceneRef.current
    if (!scene) return

    const handleMouseDown = (e: MouseEvent) => {
      lastDown.current = { x: e.clientX, y: e.clientY, time: Date.now() }
    }

    const handleMouseUp = (e: MouseEvent) => {
      if (!lastDown.current) return
      
      const dx = Math.abs(e.clientX - lastDown.current.x)
      const dy = Math.abs(e.clientY - lastDown.current.y)
      const dt = Date.now() - lastDown.current.time

      // Standard click detection threshold
      if (dx < 5 && dy < 5 && dt < 300) {
        console.info('[PixelCity] MouseUp detected as click. Searching path...')
        
        // COMPOSED PATH: The most robust way to find the clicked building
        // effectively looking through all layers in the DOM path
        const path = e.composedPath() as HTMLElement[]
        const buildingElement = path.find(el => el.classList && el.classList.contains('pixel-building'))
        const nodeId = buildingElement?.dataset?.nodeId

        if (nodeId) {
          console.info(`[PixelCity] Clicked building: ${nodeId}`)
          const node = graph.nodes.find(n => n.id === nodeId)
          if (node) {
            handleBuildingClick(node)
          } else {
            console.warn(`[PixelCity] Node found in DOM but not in graph: ${nodeId}`)
          }
        } else {
          console.info('[PixelCity] No building found in event path')
        }
      }
      lastDown.current = null
    }

    // Use capture phase to get ahead of the zoom library
    scene.addEventListener('mousedown', handleMouseDown, true)
    scene.addEventListener('mouseup', handleMouseUp, true)

    return () => {
      scene.removeEventListener('mousedown', handleMouseDown, true)
      scene.removeEventListener('mouseup', handleMouseUp, true)
    }
  }, [graph.nodes, handleBuildingClick])

  const errorCount = graph.nodes.filter(n => n.status === 'ERROR').length
  const warnCount = graph.nodes.filter(n => n.status === 'WARNING').length
  const healthyCount = graph.nodes.filter(n => n.status === 'HEALTHY').length

  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', background: '#0d1117' }}>
      {/* City header */}
      <div style={{
        padding: '10px 20px',
        borderBottom: '1px solid #21262d',
        display: 'flex', alignItems: 'center', gap: 16, flexShrink: 0, background: '#161b22',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 16 }}>🏙</span>
          <span style={{ color: '#e6edf3', fontSize: 11, fontWeight: 700, fontFamily: "'JetBrains Mono', monospace", letterSpacing: '0.06em' }}>
            PIXEL CITY: EIXAMPLE
          </span>
          <span style={{ color: '#6e7681', fontSize: 8, fontFamily: "'JetBrains Mono', monospace" }}>
            — watch out for UFO overflights
          </span>
        </div>
        <div style={{ flex: 1 }} />
        <div style={{ display: 'flex', gap: 10, fontSize: 9, fontFamily: "'JetBrains Mono', monospace" }}>
          <CityStatBadge count={errorCount} label="Devastated" color="#f85149" bg="rgba(248,81,73,0.1)" />
          <CityStatBadge count={warnCount} label="WIP" color="#d29922" bg="rgba(210,153,34,0.1)" />
          <CityStatBadge count={healthyCount} label="Complete" color="#3fb950" bg="rgba(63,185,80,0.1)" />
        </div>
      </div>

      {/* City scene */}
      <div className="pixel-city-wrapper">
        <TransformWrapper
          initialScale={1}
          minScale={0.1}
          maxScale={5}
          centerOnInit={true}
        >
          <TransformComponent wrapperStyle={{ width: '100%', height: '100%' }}>
            <div className="pixel-city-scene" ref={sceneRef}>
              {/* Sky */}
              <div className="city-sky">
          {stars.map(s => (
            <div key={s.key} className="pixel-star" style={{ left: s.left, top: s.top, width: s.size, height: s.size, animationDelay: s.delay }} />
          ))}
          {clouds.map(c => (
            <div key={c.key} className="pixel-cloud" style={{ top: c.top, animationDelay: c.delay, width: c.width }} />
          ))}
          {showUfo && (
            <div className="ufo">
              <div className="ufo-dome" />
              <div className="ufo-beam" />
            </div>
          )}
        </div>

        {/* City blocks container */}
        <div className="city-blocks">
          {gridRows.map((rowBlocks, rowIdx) => {
            const people = generatePeople(rowIdx)
            const rng = seededRandom(rowIdx)

            return (
              <div key={rowIdx} style={{ display: 'flex', flexDirection: 'column' }}>
                <div className="city-block-row">
                  {rowBlocks.map((block, blockIdx) => {
                    const decos = generateDecorations(blockIdx, rowIdx, block.length)
                    let decoIdx = 0

                    return (
                      <div key={`block-${rowIdx}-${blockIdx}`} style={{ display: 'flex' }}>
                        {/* BARCELONA BLOCK */}
                        <div className="barcelona-block">
                          <div className="city-sidewalk top-sidewalk" />
                          <div className="city-buildings-row">
                            {decos[0] && <Decoration item={decos[decoIdx++]} />}

                            {block.map((node, i) => {
                              const nodeRng = seededRandom(hashString(node.id) + rowIdx)
                              const hasCatOnRoof = nodeRng() > 0.85
                              return (
                                <span key={node.id} style={{ display: 'contents' }}>
                                  <PixelBuilding
                                    node={node}
                                    isSelected={state.selectedNode?.id === node.id}
                                    onClick={() => {}} // No longer used, handled by delegation
                                    hasCatOnRoof={hasCatOnRoof}
                                  />
                                  {i < block.length - 1 && decoIdx < decos.length - 1 && (
                                    <Decoration item={decos[decoIdx++]} />
                                  )}
                                </span>
                              )
                            })}
                            {decoIdx < decos.length && <Decoration item={decos[decos.length - 1]} />}
                          </div>
                          <div className="city-sidewalk bottom-sidewalk" />
                        </div>

                        {/* VERTICAL CROSS STREET (except after the last block) */}
                        {blockIdx < rowBlocks.length - 1 && (
                          <div className="cross-street">
                            <div className="cross-street-sidewalk-gap" />
                            <div className="vertical-road" />
                            <div className="cross-street-sidewalk-gap" />
                          </div>
                        )}
                      </div>
                    )
                  })}
                </div>

                {/* MAIN HORIZONTAL ROAD UNDER EACH ROW */}
                <div style={{ position: 'relative' }}>
                  <div className={`city-road main-road ${rowIdx === gridRows.length - 1 ? '' : 'intersection'}`}>
                    {/* Crosswalk markings where the vertical streets would intersect */}
                    {Array.from({ length: BLOCKS_PER_ROW - 1 }).map((_, intersectionIdx) => (
                      <div key={`cw-${intersectionIdx}`} style={{
                        position: 'absolute',
                        left: `calc(${((intersectionIdx + 1) / BLOCKS_PER_ROW) * 100}% - 20px)`,
                        top: 0, bottom: 0, width: 40,
                        display: 'flex', justifyContent: 'space-between'
                      }}>
                        <div className="crosswalk left" />
                        <div className="crosswalk right" />
                      </div>
                    ))}

                    {/* Events */}
                    {showClown && rowIdx === 0 && (
                      <div className="clown-running">
                        <div className="legs" />
                      </div>
                    )}

                    {/* Normal People */}
                    {people.map(p => (
                      <div
                        key={p.key}
                        className={`pixel-person ${p.color} ${p.direction}`}
                        style={{ top: p.top, animationDelay: p.delay, ['--walk-duration' as string]: p.duration }}
                      >
                        <div className="legs" />
                      </div>
                    ))}
                  </div>
                </div>
              </div>
            )
          })}

          {/* Empty state */}
          {sortedNodes.length === 0 && (
            <div style={{
              display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
              width: '100%', padding: 60, color: '#6e7681', fontSize: 11, textAlign: 'center',
            }}>
              <span style={{ fontSize: 40, marginBottom: 12 }}>🏗</span>
              <span>No buildings to display. Run an analysis first.</span>
            </div>
          )}
        </div>
            </div>
          </TransformComponent>
        </TransformWrapper>
      </div>
    </div>
  )
}

function hashString(s: string) {
  let hash = 0
  for (let i = 0; i < s.length; i++) hash = Math.imul(31, hash) + s.charCodeAt(i) | 0
  return hash
}

// ── Decoration components ──
function Decoration({ item }: { item: { type: 'tree' | 'lamp' | 'bench' | 'grass'; key: string; hasCat?: boolean } }) {
  switch (item.type) {
    case 'tree':
      return (
        <div className="pixel-tree" key={item.key}>
          <div className="tree-crown" />
          <div className="tree-trunk" />
        </div>
      )
    case 'lamp':
      return (
        <div className="street-light" key={item.key}>
          <div className="lamp-arm"><div className="lamp-bulb" /></div>
          <div className="lamp-pole" />
          <div className="lamp-cone" />
        </div>
      )
    case 'bench':
      return (
        <div className="pixel-bench" key={item.key}>
          <div className="bench-seat">
            {item.hasCat && <div className="cat-sleeping" />}
          </div>
          <div className="bench-leg left" />
          <div className="bench-leg right" />
        </div>
      )
    case 'grass':
      return (
        <div className="grass-patch" key={item.key}>
          <div className="grass-blade" /><div className="grass-blade" /><div className="grass-blade" /><div className="grass-blade" />
          <div className="grass-ground" />
        </div>
      )
  }
}

function CityStatBadge({ count, label, color, bg }: { count: number; label: string; color: string; bg: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 5, background: bg, border: `1px solid ${color}33`, padding: '3px 8px', borderRadius: 5 }}>
      <span style={{ color, fontWeight: 700 }}>{count}</span>
      <span style={{ color: '#8b949e' }}>{label}</span>
    </div>
  )
}
