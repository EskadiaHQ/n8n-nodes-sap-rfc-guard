import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const nodeSource = readFileSync(
	new URL('../nodes/SapRfcGuard/SapRfcGuard.node.ts', import.meta.url),
	'utf8',
);
const lightIcon = readFileSync(
	new URL('../nodes/SapRfcGuard/sapRfcGuard-v045.svg', import.meta.url),
	'utf8',
);
const darkIcon = readFileSync(
	new URL('../nodes/SapRfcGuard/sapRfcGuard-v045.dark.svg', import.meta.url),
	'utf8',
);

describe('RFC Guard icon family', () => {
	it('uses the versioned HANA-family connection artwork without an embedded legacy image', () => {
		assert.match(nodeSource, /file:sapRfcGuard-v045\.svg/);
		assert.match(nodeSource, /file:sapRfcGuard-v045\.dark\.svg/);
		assert.equal(lightIcon, darkIcon);
		assert.match(lightIcon, /fill="#102D63"/);
		assert.match(lightIcon, /fill="#12C8D4"/);
		assert.doesNotMatch(lightIcon, /<image|data:image/);
	});
});
