import { useCallback, useEffect, useMemo } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  useNodesState,
  useEdgesState,
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

  const [nodes, setNodes, onNodesChange] = useNodesState<Node>(buildInitialNodes(graph.nodes))
  const [edges, setEdges] = useEdgesState<Edge>([])

  // Rebuild nodes when graph data changes, preserving drag positions
  useEffect(() => {
    setNodes(prev => {
      const posMap = new Map(prev.map(n => [n.id, n.position]))
      return graph.nodes.map((gn, idx) => ({
        id: gn.id,
        type: 'custom',
        position: posMap.get(gn.id) ?? gn.position ?? {
          x: (idx % 5) * 230 + 40,
          y: Math.floor(idx / 5) * 190 + 40,
        },
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

        let strokeColor = '#30363d'
        if (hl && !isCycle) strokeColor = '#00f0ff' // Neon Blue
        else if (isCycle) strokeColor = '#f0883e'

        return {
          id: e.id,
          source: e.source,
          target: e.target,
          animated: e.animated || hl || isCycle,
          style: {
            stroke: strokeColor,
            strokeWidth: hl ? 3 : isCycle ? 1.3 : 1,
            strokeDasharray: isCycle ? '5 3' : undefined,
            filter: hl ? 'drop-shadow(0 0 5px #00f0ff)' : undefined,
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
    <div style={{ width: '100%', height: '100%', background: '#0d1117' }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onNodeClick={onNodeClick}
        onNodeDoubleClick={onNodeDoubleClick}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        minZoom={0.05}
        maxZoom={3}
        proOptions={{ hideAttribution: true }}
        style={{ background: '#0d1117' }}
      >
        <Background variant={BackgroundVariant.Dots} gap={28} size={1} color="#21262d" />
        <Controls
          showInteractive={false}
          style={{ background: '#161b22', border: '1px solid #30363d', borderRadius: 7 }}
        />
        <MiniMap
          style={{ background: '#161b22', border: '1px solid #30363d', borderRadius: 8 }}
          nodeColor={miniMapNodeColor}
          maskColor="rgba(0,0,0,0.5)"
          nodeBorderRadius={4}
        />
      </ReactFlow>
    </div>
  )
}
