import { useCallback, useMemo } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  type Node,
  type Edge,
  type NodeMouseHandler,
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

export function NeuroMap({ graph }: NeuroMapProps) {
  const { state, dispatch } = useAppStore()

  const nodes: Node[] = useMemo(() => {
    return graph.nodes.map((graphNode) => ({
      id: graphNode.id,
      type: 'custom',
      position: graphNode.position ?? { x: Math.random() * 800, y: Math.random() * 600 },
      data: {
        node: graphNode,
        isSelected: state.selectedNode?.id === graphNode.id,
        isHighlighted: state.highlightedNodes.includes(graphNode.id),
      },
    }))
  }, [graph.nodes, state.selectedNode, state.highlightedNodes])

  const edges: Edge[] = useMemo(() => {
    return graph.edges.map((graphEdge) => ({
      id: graphEdge.id,
      source: graphEdge.source,
      target: graphEdge.target,
      animated: graphEdge.animated || state.highlightedNodes.includes(graphEdge.source),
      style: {
        stroke: state.highlightedNodes.includes(graphEdge.source)
          ? 'rgba(139,92,246,0.8)'
          : 'rgba(100,116,139,0.4)',
        strokeWidth: state.highlightedNodes.includes(graphEdge.source) ? 2 : 1,
      },
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: 'rgba(100,116,139,0.4)',
        width: 12,
        height: 12,
      },
    }))
  }, [graph.edges, state.highlightedNodes])

  const onNodeClick: NodeMouseHandler = useCallback((_event, node) => {
    const graphNode = graph.nodes.find(n => n.id === node.id) as GraphNode | undefined
    if (!graphNode) return

    dispatch({ type: 'SELECT_NODE', payload: graphNode })
    bridge.nodeClicked(graphNode.id)

    // Request impact analysis
    bridge.requestImpact(graphNode.id)
  }, [graph.nodes, dispatch])

  const onNodeDoubleClick: NodeMouseHandler = useCallback((_event, node) => {
    const graphNode = graph.nodes.find(n => n.id === node.id)
    if (graphNode) {
      bridge.nodeDoubleClicked(graphNode.id)
    }
  }, [graph.nodes])

  const statusColor = (status: string) => {
    if (status === 'ERROR') return '#ef4444'
    if (status === 'WARNING') return '#eab308'
    return '#10b981'
  }

  return (
    <div className="w-full h-full">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodeClick={onNodeClick}
        onNodeDoubleClick={onNodeDoubleClick}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        minZoom={0.1}
        maxZoom={2}
        defaultEdgeOptions={{
          style: { stroke: 'rgba(100,116,139,0.4)', strokeWidth: 1 },
        }}
      >
        <Background
          variant={BackgroundVariant.Dots}
          gap={20}
          size={1}
          color="rgba(100,116,139,0.15)"
        />
        <Controls
          className="!bg-gray-800/90 !border-gray-700 [&>button]:!bg-gray-800 [&>button]:!border-gray-700 [&>button]:!text-gray-400 [&>button:hover]:!bg-gray-700"
        />
        <MiniMap
          className="!bg-gray-900/90 !border-gray-700"
          nodeColor={(node) => {
            const graphNode = graph.nodes.find(n => n.id === node.id)
            return statusColor(graphNode?.status ?? 'HEALTHY')
          }}
          maskColor="rgba(0,0,0,0.5)"
        />
      </ReactFlow>
    </div>
  )
}
