import { expect, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const PASSWORD = 'correct horse battery staple';   // a throwaway password for users created by this test run

function uniqueEmail(prefix) {
  return `${prefix}-${randomUUID()}@example.test`;
}

/** Promotes a registered user exactly as an operator would: set-user-role.sql as the database owner, TEST DB only. */
function setRole(email, role) {
  const url = new URL(process.env.TEST_DB_URL.replace(/^jdbc:/, ''));
  const database = url.pathname.slice(1);
  if (database !== 'ledgerline_test_db') throw new Error('E2E may only change roles in ledgerline_test_db');
  execFileSync('psql', ['-X', '-q', '-h', url.hostname, '-p', url.port || '5432', '-U', process.env.DB_OWNER_USERNAME,
    '-d', database, '-v', `email=${email}`, '-v', `role=${role}`, '-f', '../database/setup/set-user-role.sql'],
  { env: { ...process.env, PGPASSWORD: process.env.DB_OWNER_PASSWORD }, stdio: 'pipe' });
}

async function register(page, name, email) {
  await page.goto('/register');
  await page.getByLabel('Full name').fill(name);
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Create account' }).click();
  await expect(page.getByText('Registration complete')).toBeVisible();
}

async function signIn(page, email) {
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
}

async function openAccount(page, name) {
  await page.getByRole('button', { name: 'Open an account' }).click();
  await page.getByLabel('Account name').fill(name);
  await page.getByRole('button', { name: 'Open account' }).click();
  await page.getByRole('link', { name }).click();
  await expect(page.getByRole('heading', { name })).toBeVisible();
  return page.getByTestId('full-account-id').first().getAttribute('data-id');
}

async function move(page, tab, amount, confirm) {
  await page.getByRole('tab', { name: tab }).click();
  await page.getByLabel('Amount (₹)').fill(amount);
  await page.getByRole('button', { name: confirm.submit }).click();
  if (confirm.confirm) {
    await page.getByRole('button', { name: confirm.confirm }).click();
  }
  return page.getByTestId('posting-result');
}

test('account holders deposit, withdraw, see a refusal and transfer, with matching statements', async ({ browser }) => {
  const alice = await (await browser.newContext()).newPage();
  const bob = await (await browser.newContext()).newPage();
  const aliceEmail = uniqueEmail('e2e-alice');
  const bobEmail = uniqueEmail('e2e-bob');

  await register(alice, 'Alice E2E', aliceEmail);
  await signIn(alice, aliceEmail);
  await expect(alice.getByRole('heading', { name: 'My accounts' })).toBeVisible();
  await expect(alice.getByText('You have no accounts yet.')).toBeVisible();
  const aliceAccount = await openAccount(alice, 'Everyday');

  await expect(await move(alice, 'Deposit', '500', { submit: 'Deposit' })).toContainText('Deposit posted: ₹500.00');
  await expect(alice.locator('.account-header__amount')).toHaveText('₹500.00');
  await expect(await move(alice, 'Withdraw', '200', { submit: 'Withdraw', confirm: 'Confirm withdraw' }))
    .toContainText('Withdrawal posted: ₹200.00');
  const refused = await move(alice, 'Withdraw', '1000', { submit: 'Withdraw', confirm: 'Confirm withdraw' });
  await expect(refused).toContainText('Refused');
  await expect(refused).toContainText('Insufficient funds');
  await expect(alice.locator('.account-header__amount')).toHaveText('₹300.00');

  await register(bob, 'Bob E2E', bobEmail);
  await signIn(bob, bobEmail);
  const bobAccount = await openAccount(bob, 'Savings');

  await alice.getByRole('tab', { name: 'Transfer' }).click();
  await alice.getByLabel('Recipient account ID').fill(bobAccount);
  await alice.getByLabel('Amount (₹)').fill('100');
  await alice.getByRole('button', { name: 'Send' }).click();
  await alice.getByRole('button', { name: 'Confirm send' }).click();
  await expect(alice.getByTestId('posting-result')).toContainText('Transfer posted: ₹100.00');
  await expect(alice.locator('.account-header__amount')).toHaveText('₹200.00');

  // Alice's statement: deposit, withdrawal, refused attempt, transfer out, with running balances.
  await expect(alice.getByTestId('line-1')).toContainText('Deposit');
  await expect(alice.getByTestId('line-1')).toContainText('₹500.00');
  await expect(alice.getByTestId('line-2')).toContainText('Withdrawal');
  await expect(alice.getByTestId('line-2')).toContainText('₹300.00');
  await expect(alice.getByTestId('line-3')).toContainText('Refused');
  await expect(alice.getByTestId('line-3')).toContainText('Insufficient funds');
  await expect(alice.getByTestId('line-3')).toContainText('₹300.00');
  await expect(alice.getByTestId('line-4')).toContainText('Transfer out');
  await expect(alice.getByTestId('line-4')).toContainText('₹200.00');

  // Bob sees the credit, initiated by someone else.
  await bob.getByRole('link', { name: '← My accounts' }).click();
  await bob.getByRole('link', { name: 'Savings' }).click();
  await expect(bob.getByTestId('line-1')).toContainText('Transfer in');
  await expect(bob.getByTestId('line-1')).toContainText('₹100.00');
  await expect(bob.getByTestId('line-1')).not.toContainText('You');
  await expect(bob.locator('.account-header__amount')).toHaveText('₹100.00');
  expect(aliceAccount).not.toBe(bobAccount);
});

test('an auditor sees read-only oversight and balanced books', async ({ page }) => {
  const email = uniqueEmail('e2e-auditor');
  await register(page, 'Auditor E2E', email);
  setRole(email, 'AUDITOR');
  await signIn(page, email);
  await expect(page.getByRole('heading', { name: 'All accounts' })).toBeVisible();
  await expect(page.getByText(/Read-only oversight/)).toBeVisible();
  // The test database holds many accounts (oldest first), so open whichever statement is listed first.
  await page.getByRole('link', { name: 'Statement →' }).first().click();
  await expect(page.getByText(/read-only/).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Move money' })).toHaveCount(0);
  await page.getByRole('link', { name: '← All accounts' }).click();
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Reconciliation' }).click();
  await expect(page.getByText('Books balanced')).toBeVisible();
  await expect(page.getByRole('button', { name: /Deposit|Withdraw|Open an account/ })).toHaveCount(0);
});

test('the session lives only in memory: reloading signs out', async ({ page }) => {
  const email = uniqueEmail('e2e-reload');
  await register(page, 'Reload E2E', email);
  await signIn(page, email);
  await expect(page.getByRole('heading', { name: 'My accounts' })).toBeVisible();
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
});
