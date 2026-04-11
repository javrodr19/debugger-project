import { useCallback, useEffect, useRef } from 'react'
import type { ProjectGraph, GraphNode } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'
import { PixelBuilding } from './PixelBuilding'
import { TransformWrapper, TransformComponent } from 'react-zoom-pan-pinch'
import { useCitySimulation } from './useCitySimulation'
import { useCityGrid } from './useCityGrid'
import { Decoration, CityStatBadge, hashString, seededRandom } from './subcomponents/CityElements'
import './pixelcity.css'

interface PixelCityProps {
  graph: ProjectGraph
}

const BLOCKS_PER_ROW = 3

export function PixelCity({ graph }: PixelCityProps) {
  const { state, dispatch } = useAppStore()
  const sceneRef = useRef<HTMLDivElement>(null)
  const lastDown = useRef<{ x: number, y: number, time: number } | null>(null)

  const { showClown, showUfo, stars, clouds } = useCitySimulation()
  const { sortedNodes, gridRows, generatePeople, generateDecorations } = useCityGrid(graph.nodes)

  const handleBuildingClick = useCallback((node: GraphNode) => {
    dispatch({ type: 'SELECT_NODE', payload: node })
    bridge.nodeClicked(node.id)
    bridge.requestImpact(node.id)
  }, [dispatch])

  useEffect(() => {
    const scene = sceneRef.current
    if (!scene) return

    const handleMouseUp = (e: MouseEvent) => {
      if (!lastDown.current) return
      const dx = Math.abs(e.clientX - lastDown.current.x)
      const dy = Math.abs(e.clientY - lastDown.current.y)
      const dt = Date.now() - lastDown.current.time

      if (dx < 5 && dy < 5 && dt < 300) {
        const path = e.composedPath() as HTMLElement[]
        const buildingElement = path.find(el => el.classList && el.classList.contains('pixel-building'))
        const nodeId = buildingElement?.dataset?.nodeId
        if (nodeId) {
          const node = graph.nodes.find(n => n.id === nodeId)
          if (node) handleBuildingClick(node)
        }
      }
      lastDown.current = null
    }

    const handleMouseDown = (e: MouseEvent) => {
      lastDown.current = { x: e.clientX, y: e.clientY, time: Date.now() }
    }

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
      <header style={{
        padding: '10px 20px', borderBottom: '1px solid #21262d',
        display: 'flex', alignItems: 'center', gap: 16, flexShrink: 0, background: '#161b22',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ fontSize: 16 }}>🏙</span>
          <span style={{ color: '#e6edf3', fontSize: 11, fontWeight: 700, fontFamily: "'JetBrains Mono', monospace", letterSpacing: '0.06em' }}>PIXEL CITY: EIXAMPLE</span>
        </div>
        <div style={{ flex: 1 }} />
        <div style={{ display: 'flex', gap: 10, fontSize: 9, fontFamily: "'JetBrains Mono', monospace" }}>
          <CityStatBadge count={errorCount} label="Devastated" color="#f85149" bg="rgba(248,81,73,0.1)" />
          <CityStatBadge count={warnCount} label="WIP" color="#d29922" bg="rgba(210,153,34,0.1)" />
          <CityStatBadge count={healthyCount} label="Complete" color="#3fb950" bg="rgba(63,185,80,0.1)" />
        </div>
      </header>

      <div className="pixel-city-wrapper">
        <TransformWrapper initialScale={1} minScale={0.1} maxScale={5} centerOnInit={true}>
          <TransformComponent wrapperStyle={{ width: '100%', height: '100%' }}>
            <div className="pixel-city-scene" ref={sceneRef}>
              <div className="city-sky">
                {stars.map(s => <div key={s.key} className="pixel-star" style={{ left: s.left, top: s.top, width: s.size, height: s.size, animationDelay: s.delay }} />)}
                {clouds.map(c => <div key={c.key} className="pixel-cloud" style={{ top: c.top, animationDelay: c.delay, width: c.width }} />)}
                {showUfo && <div className="ufo"><div className="ufo-dome" /><div className="ufo-beam" /></div>}
              </div>

              <div className="city-blocks">
                {gridRows.map((rowBlocks, rowIdx) => {
                  const people = generatePeople(rowIdx)
                  return (
                    <div key={rowIdx} style={{ display: 'flex', flexDirection: 'column' }}>
                      <div className="city-block-row">
                        {rowBlocks.map((block, blockIdx) => {
                          const decos = generateDecorations(blockIdx, rowIdx, block.length)
                          let decoIdx = 0
                          return (
                            <div key={`block-${rowIdx}-${blockIdx}`} style={{ display: 'flex' }}>
                              <div className="barcelona-block">
                                <div className="city-sidewalk top-sidewalk" />
                                <div className="city-buildings-row">
                                  {decos[0] && <Decoration item={decos[decoIdx++]} />}
                                  {block.map((node, i) => {
                                    const nodeRng = seededRandom(hashString(node.id) + rowIdx)
                                    return (
                                      <span key={node.id} style={{ display: 'contents' }}>
                                        <PixelBuilding node={node} isSelected={state.selectedNode?.id === node.id} onClick={() => {}} hasCatOnRoof={nodeRng() > 0.85} />
                                        {i < block.length - 1 && decoIdx < decos.length - 1 && <Decoration item={decos[decoIdx++]} />}
                                      </span>
                                    )
                                  })}
                                  {decoIdx < decos.length && <Decoration item={decos[decos.length - 1]} />}
                                </div>
                                <div className="city-sidewalk bottom-sidewalk" />
                              </div>
                              {blockIdx < rowBlocks.length - 1 && (
                                <div className="cross-street"><div className="cross-street-sidewalk-gap" /><div className="vertical-road" /><div className="cross-street-sidewalk-gap" /></div>
                              )}
                            </div>
                          )
                        })}
                      </div>
                      <div style={{ position: 'relative' }}>
                        <div className={`city-road main-road ${rowIdx === gridRows.length - 1 ? '' : 'intersection'}`}>
                          {Array.from({ length: BLOCKS_PER_ROW - 1 }).map((_, intersectionIdx) => (
                            <div key={`cw-${intersectionIdx}`} style={{ position: 'absolute', left: `calc(${((intersectionIdx + 1) / BLOCKS_PER_ROW) * 100}% - 20px)`, top: 0, bottom: 0, width: 40, display: 'flex', justifyContent: 'space-between' }}>
                              <div className="crosswalk left" /><div className="crosswalk right" />
                            </div>
                          ))}
                          {showClown && rowIdx === 0 && <div className="clown-running"><div className="legs" /></div>}
                          {people.map(p => (
                            <div key={p.key} className={`pixel-person ${p.color} ${p.direction}`} style={{ top: p.top, animationDelay: p.delay, ['--walk-duration' as string]: p.duration }}>
                              <div className="legs" />
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  )
                })}
                {sortedNodes.length === 0 && (
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', width: '100%', padding: 60, color: '#6e7681', fontSize: 11, textAlign: 'center' }}>
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

