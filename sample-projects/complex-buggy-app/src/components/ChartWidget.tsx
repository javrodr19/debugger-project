import React, { useState } from 'react';

export const ChartWidget = () => {
    // Another null safety trigger
    const [result, setResult] = useState(undefined);
    
    return <div>{result.value}</div>;
};