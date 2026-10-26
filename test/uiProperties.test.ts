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

	it('exposes invoice creation only on the Incoming Invoice resource', () => {
		const operationProperties = (new SapRfcGuard().description.properties as INodeProperties[]).filter(
			(property) => property.name === 'operation' && property.type === 'options',
		);
		const invoiceOperation = operationProperties.find(
			(property) => property.displayOptions?.show?.resource?.includes('incomingInvoice'),
		);
		const userOperation = operationProperties.find(
			(property) => property.displayOptions?.show?.resource?.includes('userAdministration'),
		);
		assert.equal(invoiceOperation?.options?.some((option) => option.value === 'create'), true);
		assert.equal(userOperation?.options?.some((option) => option.value === 'create'), false);
	});

	it('exposes the exact-confirmation goods receipt on its own resource', () => {
		const properties = new SapRfcGuard().description.properties as INodeProperties[];
		const operation = properties.find(
			(property) =>
				property.name === 'operation' &&
				property.displayOptions?.show?.resource?.includes('goodsMovement'),
		);
		const confirmation = properties.find(
			(property) => property.name === 'goodsReceiptConfirmation',
		);
		assert.equal(operation?.options?.some((option) => option.value === 'createGoodsReceipt'), true);
		assert.equal(confirmation?.required, true);
	});
});
