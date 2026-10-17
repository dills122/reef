import { cn } from '#lib/utils.js';

export function signed(n: number): string {
	return `${n > 0 ? '+' : ''}${n.toLocaleString()}`;
}

export function pnlClass(n: number): string {
	return cn(n > 0 && 'text-accent', n < 0 && 'text-destructive');
}

export function formatDateTime(value: string): string {
	return new Date(value).toLocaleString();
}

export function formatTimestamp(value?: string | null): string {
	if (!value) return 'none';
	return formatDateTime(value);
}
