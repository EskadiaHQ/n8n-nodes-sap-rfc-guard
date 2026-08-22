import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';

const nodeSource = readFileSync(
	new URL('../nodes/SapRfcGuard/SapRfcGuard.node.ts', import.meta.url),
	'utf8',
);
const credentialSource = readFileSync(
	new URL('../credentials/SapRfcGuardApi.credentials.ts', import.meta.url),
	'utf8',
);
const lightIcon = readFileSync(
	new URL('../nodes/SapRfcGuard/sapRfcGuard-v047.svg', import.meta.url),
	'utf8',
);
const darkIcon = readFileSync(
	new URL('../nodes/SapRfcGuard/sapRfcGuard-v047.dark.svg', import.meta.url),
	'utf8',
);
const credentialIcon = readFileSync(
	new URL('../credentials/sapRfcGuardCredential-v047.svg', import.meta.url),
	'utf8',
);

describe('RFC Guard icon family', () => {
	it('uses the approved versioned artwork on the node and credential', () => {
		assert.match(nodeSource, /file:sapRfcGuard-v047\.svg/);
		assert.match(nodeSource, /file:sapRfcGuard-v047\.dark\.svg/);
		assert.match(credentialSource, /file:sapRfcGuardCredential-v047\.svg/);
		assert.equal(lightIcon, darkIcon);
		assert.equal(lightIcon, credentialIcon);
		const png = Buffer.from(lightIcon.match(/base64,([^"']+)/)?.[1] ?? '', 'base64');
		assert.equal(png.readUInt32BE(16), 1024);
		assert.equal(png.readUInt32BE(20), 1024);
		assert.equal(
			createHash('sha256').update(png).digest('hex'),
			'f9a80342b157382332987363ddbc864e6b4d9451c63ba268edfab83d19d18238',
		);
	});
});
