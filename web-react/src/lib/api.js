export function canonicalApiPath(rawPath) {
    const path = rawPath.startsWith('/') ? rawPath : `/${rawPath}`;
    return path.startsWith('/api/') || path === '/api' ? path : `/api${path}`;
}

export async function secureFetch(pathWithQuery) {
    return fetch(canonicalApiPath(pathWithQuery), { credentials: 'same-origin' });
}
