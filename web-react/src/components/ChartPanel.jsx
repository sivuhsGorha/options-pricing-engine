export default function ChartPanel({ id, chartRef, title, headerContent, className, expanded, onToggle }) {
    return (
        <div className={`panel chart-panel ${className} ${expanded ? 'expanded' : ''}`}>
            <div className="panel-header">
                {headerContent || <span>{title}</span>}
                <button type="button" className="expand-btn" onClick={onToggle}>
                    {expanded ? '[SHRINK]' : '[EXPAND]'}
                </button>
            </div>
            <div className="panel-content no-padding">
                <div id={id} ref={chartRef}></div>
            </div>
        </div>
    );
}
