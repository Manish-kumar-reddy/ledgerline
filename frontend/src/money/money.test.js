import { describe, expect, it } from 'vitest';
import { formatInr, isValidAmount, shortId, toMinor } from './money.js';

describe('money', () => {
  it.each([['0.01', 1], ['1', 100], ['1.5', 150], ['10.05', 1005], ['1000000', 100000000], ['999999999.99', 99999999999]])(
    'converts %s exactly to %i paise', (text, paise) => expect(toMinor(text)).toBe(paise));

  it.each(['0', '0.00', '-1', '+1', '1.001', '1e3', ' 1', '1,000', '.5', '1.', '01', '1000000000', 'NaN', '', '１'])(
    'rejects %j like the backend does', (text) => {
      expect(isValidAmount(text)).toBe(false);
      expect(() => toMinor(text)).toThrow();
    });

  it('formats paise as INR with Indian digit grouping', () => {
    expect(formatInr(0)).toBe('₹0.00');
    expect(formatInr(5)).toBe('₹0.05');
    expect(formatInr(50000)).toBe('₹500.00');
    expect(formatInr(1000000000)).toBe('₹1,00,00,000.00');
    expect(formatInr(12345678901)).toBe('₹12,34,56,789.01');
    expect(formatInr(-30000)).toBe('−₹300.00');
  });

  it('shortens ids for display', () => expect(shortId('3f2a9c1e-0000-4000-8000-000000000000')).toBe('3f2a9c1e'));
});
