export interface SapRfcGuardCredentials {
	baseUrl: string;
	apiToken: string;
	headerMode?: 'bearer' | 'xRfcGuardToken';
	sidecarMode?: 'readOnly' | 'userProvisioning';
	allowedOperations: string;
	dataFieldPoliciesJson: string;
	allowUserCreation?: boolean;
	allowInsecureHttp?: boolean;
	rejectUnauthorized?: boolean;
	connectionTimeout: number;
	requestTimeout: number;
	maxRows: number;
	maxRequestBytes: number;
	maxResponseBytes: number;
	allowAiTool?: boolean;
}

export interface RfcGuardRequestOptions {
	method: 'GET' | 'POST';
	url: string;
	headers: Record<string, string>;
	body?: Record<string, unknown>;
	json: true;
	timeout: number;
	skipSslCertificateValidation: boolean;
}

export type RfcGuardHttpRequest = (options: RfcGuardRequestOptions) => Promise<unknown>;

export interface RfcGuardMetadata {
	operation: string;
	correlationId: string;
	rowCount: number;
	rowLimit: number;
	truncated: boolean;
	readOnly: boolean;
	writeOperation?: boolean;
	source?: string;
	syntheticData?: boolean;
	backend?: {
		systemId: string;
		client: string;
		release: string;
	};
	durationMs?: number;
}
