import { describe, expect, it } from 'vitest';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

// vitest runs from web-react/, so the repository root is one level up
const here = (...parts) => resolve(process.cwd(), ...parts);
const read = (...parts) => readFileSync(here(...parts), 'utf8');

describe('the product identity', () => {
    it('is AURA-OPT in the browser tab, not the template name', () => {
        const html = read('index.html');

        expect(html).toMatch(/<title>AURA-OPT[^<]*<\/title>/);
        expect(html).not.toMatch(/<title>web-react/);
        expect(html).toMatch(/<meta name="description" content="[^"]*options[^"]*"/i);
    });

    it('names the package after the product', () => {
        const pkg = JSON.parse(read('package.json'));
        const lock = JSON.parse(read('package-lock.json'));

        expect(pkg.name).toBe('aura-opt-dashboard');
        expect(lock.name).toBe('aura-opt-dashboard');
        expect(lock.packages[''].name).toBe('aura-opt-dashboard');
    });

    it('has its own favicon, which is the mark and not the Vite template icon', () => {
        const svg = read('public', 'favicon.svg');

        expect(svg).toMatch(/<title[^>]*>AURA-OPT<\/title>/);
        expect(svg).toMatch(/viewBox="0 0 64 64"/);
        expect(svg).not.toContain('#863bff'); // the Vite template's purple
    });

    it('ships a README lockup, valid enough to render, that spells the name and says what it is', () => {
        const path = here('..', 'docs', 'logo.svg');
        expect(existsSync(path)).toBe(true);
        const svg = readFileSync(path, 'utf8');
        const doc = new DOMParser().parseFromString(svg, 'image/svg+xml');

        expect(doc.querySelector('parsererror')).toBeNull();
        expect(doc.documentElement.getAttribute('viewBox')).toMatch(/^0 0 \d+ \d+$/);
        expect(svg).toContain('AURA');
        expect(svg).toContain('OPT');
        expect(svg.toUpperCase()).toContain('PAPER');
    });

    it('carries no unused template assets', () => {
        expect(existsSync(here('public', 'icons.svg'))).toBe(false);
    });
});
