import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ChartPanel from './ChartPanel';

describe('ChartPanel', () => {
    it('offers EXPAND when collapsed and SHRINK when expanded, calling the toggle', () => {
        const onToggle = vi.fn();
        const { rerender } = render(<ChartPanel id="c" chartRef={{ current: null }} title="SMILE" className="sub-chart" expanded={false} onToggle={onToggle} />);

        expect(screen.getByText('SMILE')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '[EXPAND]' }));
        expect(onToggle).toHaveBeenCalledTimes(1);

        rerender(<ChartPanel id="c" chartRef={{ current: null }} title="SMILE" className="sub-chart" expanded={true} onToggle={onToggle} />);
        expect(screen.getByRole('button', { name: '[SHRINK]' })).toBeInTheDocument();
    });

    it('renders custom header content in place of the title', () => {
        render(<ChartPanel id="c" chartRef={{ current: null }} title="ignored" headerContent={<span>SURFACE (SSVI)</span>} className="main-chart" expanded={false} onToggle={() => {}} />);

        expect(screen.getByText('SURFACE (SSVI)')).toBeInTheDocument();
        expect(screen.queryByText('ignored')).not.toBeInTheDocument();
    });
});
