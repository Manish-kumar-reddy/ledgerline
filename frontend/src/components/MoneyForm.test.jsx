import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/errors.js';
import MoneyForm from './MoneyForm.jsx';

const ACCOUNT = '11111111-1111-4111-8111-111111111111';
const OTHER = '22222222-2222-4222-8222-222222222222';

const posted = (extra = {}) => ({ outcome: 'POSTED', operation: 'WITHDRAWAL', amountMinor: 1000, balanceMinor: 9000, replayed: false, ...extra });

function setup(kind, api) {
  const user = userEvent.setup();
  const onPosted = vi.fn();
  render(<MoneyForm kind={kind} accountId={ACCOUNT} api={api} onPosted={onPosted} />);
  return { user, onPosted };
}

describe('MoneyForm', () => {
  it('validates the amount before sending anything', async () => {
    const api = { deposit: vi.fn() };
    const { user } = setup('deposit', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '10.555');
    await user.click(screen.getByRole('button', { name: 'Deposit' }));
    expect(screen.getByText(/at most 2 decimals/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Amount (₹)'));
    await user.type(screen.getByLabelText('Amount (₹)'), '1000000.01');
    expect(screen.getByText(/₹10,00,000.00/, { selector: '.field__error' })).toBeInTheDocument();
    expect(api.deposit).not.toHaveBeenCalled();
  });

  it('confirms a withdrawal, sends the typed string and shows the new balance', async () => {
    const api = { withdraw: vi.fn().mockResolvedValue(posted()) };
    const { user, onPosted } = setup('withdraw', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '10');
    await user.click(screen.getByRole('button', { name: 'Withdraw' }));
    expect(api.withdraw).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Confirm withdraw' }));
    expect(api.withdraw).toHaveBeenCalledWith(ACCOUNT, { amount: '10', description: undefined }, expect.any(String));
    expect(await screen.findByTestId('posting-result')).toHaveTextContent('Withdrawal posted: ₹10.00');
    expect(screen.getByTestId('posting-result')).toHaveTextContent('New balance ₹90.00');
    expect(onPosted).toHaveBeenCalled();
  });

  it('shows a refusal clearly, with the unchanged balance', async () => {
    const api = { withdraw: vi.fn().mockResolvedValue({ outcome: 'REFUSED', reason: 'INSUFFICIENT_FUNDS', balanceMinor: 500, replayed: false }) };
    const { user } = setup('withdraw', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '50');
    await user.click(screen.getByRole('button', { name: 'Withdraw' }));
    await user.click(screen.getByRole('button', { name: 'Confirm withdraw' }));
    const result = await screen.findByTestId('posting-result');
    expect(result).toHaveTextContent('Refused');
    expect(result).toHaveTextContent('Insufficient funds');
    expect(result).toHaveTextContent('still ₹5.00');
  });

  it('retries a busy (503) request with the SAME idempotency key', async () => {
    const api = { deposit: vi.fn()
      .mockRejectedValueOnce(new ApiError({ status: 503, title: 'Ledger busy', retryAfterSeconds: 1 }))
      .mockResolvedValueOnce(posted({ operation: 'DEPOSIT' })) };
    const { user } = setup('deposit', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '10');
    await user.click(screen.getByRole('button', { name: 'Deposit' }));
    expect(await screen.findByText('Ledger busy')).toBeInTheDocument();
    expect(screen.getByText(/cannot be posted twice/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByTestId('posting-result');
    expect(api.deposit).toHaveBeenCalledTimes(2);
    expect(api.deposit.mock.calls[1][2]).toBe(api.deposit.mock.calls[0][2]);
  });

  it('retries after a network failure with the same key, but uses a new key once the request changes', async () => {
    const api = { deposit: vi.fn().mockRejectedValue(new ApiError({ status: 0, network: true, title: 'Cannot reach the server' })) };
    const { user } = setup('deposit', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '10');
    await user.click(screen.getByRole('button', { name: 'Deposit' }));
    await user.click(await screen.findByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(api.deposit).toHaveBeenCalledTimes(2));
    await user.type(screen.getByLabelText('Amount (₹)'), '5');           // now "105"
    await user.click(screen.getByRole('button', { name: 'Deposit' }));
    await waitFor(() => expect(api.deposit).toHaveBeenCalledTimes(3));
    const [first, second, third] = api.deposit.mock.calls.map((call) => call[2]);
    expect(second).toBe(first);
    expect(third).not.toBe(first);
  });

  it('a 409 explains that nothing was posted and offers no automatic retry', async () => {
    const api = { deposit: vi.fn().mockRejectedValue(new ApiError({ status: 409, title: 'Idempotency key reused' })) };
    const { user } = setup('deposit', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '10');
    await user.click(screen.getByRole('button', { name: 'Deposit' }));
    expect(await screen.findByText(/Nothing was posted/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('requires a full, different recipient id for transfers', async () => {
    const api = { transfer: vi.fn().mockResolvedValue(posted({ operation: 'TRANSFER' })) };
    const { user } = setup('transfer', api);
    await user.type(screen.getByLabelText('Amount (₹)'), '1');
    await user.type(screen.getByLabelText('Recipient account ID'), '1234');
    await user.click(screen.getByRole('button', { name: 'Send' }));
    expect(screen.getByText(/full account ID/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Recipient account ID'));
    await user.type(screen.getByLabelText('Recipient account ID'), ACCOUNT);
    expect(screen.getByText(/different account/)).toBeInTheDocument();
    await user.clear(screen.getByLabelText('Recipient account ID'));
    await user.type(screen.getByLabelText('Recipient account ID'), OTHER.toUpperCase());
    await user.click(screen.getByRole('button', { name: 'Send' }));
    await user.click(screen.getByRole('button', { name: 'Confirm send' }));
    expect(api.transfer).toHaveBeenCalledWith(ACCOUNT, { amount: '1', description: undefined, toAccountId: OTHER }, expect.any(String));
  });
});
