import { OperationalError } from 'n8n-workflow';

import type { SapRfcGuardCredentials } from './types';

const TOOL_NODE_TYPE = 'n8n-nodes-sap-rfc-guard.sapRfcGuardTool';

export function assertAiToolAllowed(
	nodeType: string,
	credentials: SapRfcGuardCredentials,
): void {
	if (nodeType === TOOL_NODE_TYPE && credentials.allowAiTool !== true) {
		throw new OperationalError(
			'This credential does not allow SAP RFC Guard to be used as an AI tool.',
		);
	}
}
