import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import LoginForm from './LoginForm';

describe('LoginForm', () => {
    it('reports typing and submits the form once a password is present', () => {
        const onPasswordChange = vi.fn();
        const onSubmit = vi.fn(e => e.preventDefault());
        const { rerender } = render(<LoginForm password="" onPasswordChange={onPasswordChange} error="" onSubmit={onSubmit} />);

        fireEvent.change(screen.getByLabelText('Operator password'), { target: { value: 'secret' } });
        expect(onPasswordChange).toHaveBeenCalledWith('secret');

        // The field is required: an empty controlled value must not submit (the browser blocks it), a filled one does.
        fireEvent.click(screen.getByRole('button', { name: 'Open dashboard' }));
        expect(onSubmit).not.toHaveBeenCalled();
        rerender(<LoginForm password="secret" onPasswordChange={onPasswordChange} error="" onSubmit={onSubmit} />);
        fireEvent.click(screen.getByRole('button', { name: 'Open dashboard' }));
        expect(onSubmit).toHaveBeenCalledTimes(1);
    });

    it('shows the error as an alert so screen readers announce it', () => {
        render(<LoginForm password="" onPasswordChange={() => {}} error="Authentication failed." onSubmit={() => {}} />);

        expect(screen.getByRole('alert')).toHaveTextContent('Authentication failed.');
    });

    it('uses a password field that is never rendered as plain text', () => {
        render(<LoginForm password="hunter2hunter2" onPasswordChange={() => {}} error="" onSubmit={() => {}} />);

        expect(screen.getByLabelText('Operator password')).toHaveAttribute('type', 'password');
    });
});
