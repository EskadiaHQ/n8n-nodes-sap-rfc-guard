import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import type { INodeProperties } from 'n8n-workflow';

import { SapRfcGuard } from '../nodes/SapRfcGuard/SapRfcGuard.node';

describe('typed RFC UI properties', () => {
	it('requires Plant for ATP while keeping it optional for material details', () => {
		const properties = new SapRfcGuard().description.properties as INodeProperties[];
		const plantProperties = properties.filter((property) => property.name === 'plant');

		assert.equal(plantProperties.length, 2);
		const atpPlant = plantProperties.find((property) =>
			property.displayOptions?.show?.operation?.includes('checkAvailability'),
		);
		const detailPlant = plantProperties.find((property) =>
			property.displayOptions?.show?.operation?.includes('getDetails'),
		);

		assert.equal(atpPlant?.required, true);
		assert.notEqual(detailPlant?.required, true);
	});
});
