import { useCallback, useEffect, useRef } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
  useReactFlow,
  type Node,
  type Edge,
  BackgroundVariant,
  MarkerType,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { CustomNode } from './CustomNode'
import type { ProjectGraph, GraphNode } from '../../types'
import { useAppStore } from '../../stores/appStore'
import { bridge } from '../../bridge/pluginBridge'

const nodeTypes = { custom: CustomNode }

interface NeuroMapProps {
  graph: ProjectGraph
}

function buildInitialNodes(graphNodes: ProjectGraph['nodes']): Node[] {
  return graphNodes.map((gn, idx) => ({
    id: gn.id,
    type: 'custom',
    position: gn.position ?? {
      x: (idx % 5) * 230 + 40,
      y: Math.floor(idx / 5) * 190 + 40,
    },
    data: { node: gn, isSelected: false, isHighlighted: false, isDebugActive: false },
    zIndex: 0,
  }))
}

export function NeuroMap({ graph }: NeuroMapProps) {
  const { state, dispatch } = useAppStore()
  const { setViewport, getViewport } = useReactFlow()
  const containerRef = useRef<HTMLDivElement>(null)

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>(buildGroupedNodes(graph.nodes))
  const [edges, setEdges] = useEdgesState<Edge>([])

  // Custom wheel handler — 2x zoom speed, zooms toward cursor
  const onWheel = useCallback((e: React.WheelEvent) => {
    e.preventDefault()
    const { x, y, zoom } = getViewport()
    const rect = containerRef.current?.getBoundingClientRect()
    if (!rect) return
    const mouseX = e.clientX - rect.left
    const mouseY = e.clientY - rect.top
    const scaleFactor = e.deltaY < 0 ? 1.35 : 0.74   // ~2x speed vs default
    const newZoom = Math.max(0.03, Math.min(4, zoom * scaleFactor))
    const newX = mouseX + (x - mouseX) * (newZoom / zoom)
    const newY = mouseY + (y - mouseY) * (newZoom / zoom)
    setViewport({ x: newX, y: newY, zoom: newZoom }, { duration: 0 })
  }, [getViewport, setViewport])

  // Rebuild nodes when graph data changes, preserving drag positions
  useEffect(() => {
    const grouped = buildGroupedNodes(graph.nodes)
    const groupedPosMap = new Map(grouped.map(n => [n.id, n.position]))
    setNodes(prev => {
      const dragPosMap = new Map(prev.map(n => [n.id, n.position]))
      return graph.nodes.map(gn => ({
        id: gn.id,
        type: 'custom',
        position: dragPosMap.get(gn.id) ?? groupedPosMap.get(gn.id) ?? { x: 40, y: 40 },
        data: { node: gn, isSelected: false, isHighlighted: false, isDebugActive: false },
        zIndex: 0,
      }))
    })
  }, [graph.nodes, setNodes])

  // Update selection/highlight/debug/zIndex — only touches data + zIndex, never position
  useEffect(() => {
    setNodes(prev =>
      prev.map(n => {
        const nextSelected    = state.selectedNode?.id === n.id
        const nextHighlighted = state.highlightedNodes.includes(n.id)
        const nextDebugActive = state.debugFrame?.nodeId === n.id
        const nextFocused     = state.focusedNodeId === n.id

        const d = n.data as { node: GraphNode; isSelected: boolean; isHighlighted: boolean; isDebugActive: boolean }

        // Compute z-index: focused (expanded) nodes go on top
        const nextZIndex = nextFocused ? 1000 : nextDebugActive ? 500 : nextSelected ? 100 : 0

        if (
          d.isSelected === nextSelected &&
          d.isHighlighted === nextHighlighted &&
          d.isDebugActive === nextDebugActive &&
          n.zIndex === nextZIndex
        ) return n

        return {
          ...n,
          zIndex: nextZIndex,
          data: { ...d, isSelected: nextSelected, isHighlighted: nextHighlighted, isDebugActive: nextDebugActive },
        }
      })
    )
  }, [state.selectedNode, state.highlightedNodes, state.debugFrame, state.focusedNodeId, setNodes])

  // Rebuild edges — cycle edges in orange
  useEffect(() => {
    setEdges(
      graph.edges.map(e => {
        const hl = state.highlightedNodes.includes(e.source)
        const isCycle = e.isCycle === true

        let strokeColor = 'rgba(48,54,61,0.45)'   // default: 55% más sutil
        if (hl && !isCycle) strokeColor = 'rgba(0,240,255,0.75)'
        else if (isCycle)   strokeColor = 'rgba(240,136,62,0.65)'

        return {
          id: e.id,
          source: e.source,
          target: e.target,
          animated: e.animated || hl || isCycle,
          style: {
            stroke: strokeColor,
            strokeWidth: hl ? 2 : isCycle ? 1 : 0.8,
            strokeDasharray: isCycle ? '5 3' : undefined,
            filter: hl ? 'drop-shadow(0 0 4px rgba(0,240,255,0.5))' : undefined,
          },
          markerEnd: {
            type: MarkerType.ArrowClosed,
            color: strokeColor,
            width: 9,
            height: 9,
          },
        }
      })
    )
  }, [graph.edges, state.highlightedNodes, setEdges])

  const onNodeClick = useCallback((_: React.MouseEvent, node: Node) => {
    const gn = graph.nodes.find(n => n.id === node.id) as GraphNode | undefined
    if (!gn) return
    dispatch({ type: 'SELECT_NODE', payload: gn })
    bridge.nodeClicked(gn.id)
    bridge.requestImpact(gn.id)
  }, [graph.nodes, dispatch])

  const onNodeDoubleClick = useCallback((_: React.MouseEvent, node: Node) => {
    const gn = graph.nodes.find(n => n.id === node.id)
    if (gn) bridge.nodeDoubleClicked(gn.id)
  }, [graph.nodes])

  const miniMapNodeColor = useCallback((n: Node) => {
    const gn = graph.nodes.find(g => g.id === n.id)
    if (gn?.status === 'ERROR')   return '#f85149'
    if (gn?.status === 'WARNING') return '#d29922'
    return '#3fb950'
  }, [graph.nodes])

  return (
    <div
      ref={containerRef}
      style={{ width: '100%', height: '100%', background: '#0d1117' }}
      onWheel={onWheel}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onNodeClick={onNodeClick}
        onNodeDoubleClick={onNodeDoubleClick}
        fitView
        fitViewOptions={{ padding: 0.25 }}
        minZoom={0.03}
        maxZoom={4}
        panOnDrag
        panOnScroll={false}
        zoomOnScroll={false}
        zoomOnPinch
        zoomOnDoubleClick={false}
        selectNodesOnDrag={false}
        proOptions={{ hideAttribution: true }}
        style={{ background: '#0d1117' }}
      >
        <Background variant={BackgroundVariant.Dots} gap={28} size={1} color="#21262d" />
        <Controls
          showInteractive={false}
          style={{ background: '#161b22', border: '1px solid #30363d', borderRadius: 0 }}
        />
        <MiniMap
          style={{ background: '#161b22', border: '1px solid #30363d', borderRadius: 0 }}
          nodeColor={miniMapNodeColor}
          maskColor="rgba(0,0,0,0.5)"
          nodeBorderRadius={0}
        />
      </ReactFlow>
    </div>
  )
}
