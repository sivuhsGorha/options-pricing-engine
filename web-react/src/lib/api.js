export function canonicalApiPath(rawPath) {
    const path = rawPath.startsWith('/') ? rawPath : `/${rawPath}`;
    return path.startsWith('/api/') || path === '/api' ? path : `/api${path}`;
}

export async function secureFetch(pathWithQuery) {
    return fetch(canonicalApiPath(pathWithQuery), { credentials: 'same-origin' });
}

/** State-changing call. The browser adds the Origin header to a same-origin POST, which the server requires. */
export async function securePost(pathWithQuery, body) {
    return fetch(canonicalApiPath(pathWithQuery), {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {})
    });
}
