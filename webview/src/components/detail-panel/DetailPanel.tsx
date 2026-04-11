import React from 'react'
import { useAppStore } from '../../stores/appStore'
import { OverviewPanel } from './OverviewPanel'
import { NodePanel } from './NodePanel'
import { C } from './subcomponents/DetailUtils'

export function DetailPanel() {
  const { state, dispatch } = useAppStore()
  const { selectedNode, selectedIssue, systemExplanation, graph, metrics } = state

  if (!selectedNode) {
    return (
      <OverviewPanel
        graph={graph}
        metrics={metrics}
        systemExplanation={systemExplanation}
        dispatch={dispatch}
      />
    )
  }

  return (
    <NodePanel
      selectedNode={selectedNode}
      selectedIssue={selectedIssue}
      explanations={state.explanations}
      pendingFixes={state.pendingFixes}
      loadingFix={state.loadingFix}
      dispatch={dispatch}
    />
  )
}
