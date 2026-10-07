export default function LoginForm({ password, onPasswordChange, error, onSubmit }) {
    return (
        <main className="login-screen">
            <form className="login-form" onSubmit={onSubmit}>
                <div className="login-kicker">AURA-OPT / OPERATOR ACCESS</div>
                <h1>Sign in</h1>
                <label htmlFor="operator-password">Operator password</label>
                <input id="operator-password" type="password" autoComplete="current-password" value={password}
                    onChange={(event) => onPasswordChange(event.target.value)} required />
                {error && <p className="login-error" role="alert">{error}</p>}
                <button type="submit">Open dashboard</button>
            </form>
        </main>
    );
}
