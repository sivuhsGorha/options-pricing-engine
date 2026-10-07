import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Vitest globals are off (tests import what they use), so Testing Library's automatic cleanup is not
// registered; without this each test's render would stay in the document and leak into the next.
afterEach(cleanup);
