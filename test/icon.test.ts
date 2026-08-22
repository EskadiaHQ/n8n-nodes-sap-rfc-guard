import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const nodeSource = readFileSync(
	new URL('../nodes/SapRfcGuard/SapRfcGuard.node.ts', import.meta.url),
	'utf8',
);
const lightIcon = readFileSync(
	new URL('../nodes/SapRfcGuard/sapRfcGuard-v046.svg', import.meta.url),
	'utf8',
);
const darkIcon = readFileSync(
	new URL('../nodes/SapRfcGuard/sapRfcGuard-v046.dark.svg', import.meta.url),
	'utf8',
);

describe('RFC Guard icon family', () => {
	it('uses the versioned HANA-family artwork with a legible connection badge', () => {
		assert.match(nodeSource, /file:sapRfcGuard-v046\.svg/);
		assert.match(nodeSource, /file:sapRfcGuard-v046\.dark\.svg/);
		assert.equal(lightIcon, darkIcon);
		assert.match(lightIcon, /<image[^>]+data:image\/png;base64,/);
		assert.match(lightIcon, /<circle cx="49" cy="49" r="14\.5"/);
		assert.match(lightIcon, /<circle cx="41\.5" cy="49" r="4\.2"/);
		assert.match(lightIcon, /<circle cx="56\.5" cy="49" r="4\.2"/);
		assert.match(lightIcon, /fill="#12C8D4"/);
		assert.doesNotMatch(lightIcon, /M32 4 55 17/);
	});
});
