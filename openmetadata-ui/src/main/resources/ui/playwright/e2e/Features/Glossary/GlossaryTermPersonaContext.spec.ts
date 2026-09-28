/*
 *  Copyright 2026 Collate.
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
import type { Page } from '@playwright/test';
import { expect, test } from '../../../support/fixtures/userPages';
import { Glossary } from '../../../support/glossary/Glossary';
import { GlossaryTerm } from '../../../support/glossary/GlossaryTerm';
import { PersonaClass } from '../../../support/persona/PersonaClass';
import { UserClass } from '../../../support/user/UserClass';
import { getDefaultAdminAPIContext } from '../../../utils/common';
import { waitForAllLoadersToDisappear } from '../../../utils/entity';

const glossary = new Glossary();
const editableTerm = new GlossaryTerm(glossary);
const validationTerm = new GlossaryTerm(glossary);
const readOnlyTerm = new GlossaryTerm(glossary);
const defaultPersona = new PersonaClass();
const userPersona = new PersonaClass();
const user = new UserClass();

const openContext = async (page: Page, term: GlossaryTerm) => {
  await term.visitPage(page);
  await page.getByTestId('ai-context').click();
  await waitForAllLoadersToDisappear(page);
  await expect(page.getByTestId('term-persona-context')).toBeVisible();
};

const saveContext = async (page: Page, term: GlossaryTerm) => {
  const saveResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PATCH' &&
      new URL(response.url()).pathname ===
        `/api/v1/glossaryTerms/${term.responseData.id}`
  );
  await expect(page.getByTestId('save-term-persona-context')).toBeEnabled();
  await page.getByTestId('save-term-persona-context').click();
  expect((await saveResponse).status()).toBe(200);
  await expect(page.getByTestId('edit-term-persona-context')).toBeVisible();
};

test.describe(
  'Glossary term Persona context',
  { tag: ['@Features', '@Governance'] },
  () => {
    test.beforeAll(
      'Create isolated term context fixtures',
      async ({ browser }) => {
        const { apiContext, afterAction } = await getDefaultAdminAPIContext(
          browser
        );
        try {
          await glossary.create(apiContext);
          await Promise.all([
            editableTerm.create(apiContext),
            validationTerm.create(apiContext),
            readOnlyTerm.create(apiContext),
            defaultPersona.create(apiContext),
            userPersona.create(apiContext),
            user.create(apiContext),
          ]);
          await readOnlyTerm.patch(apiContext, [
            {
              op: 'add',
              path: '/contextPersona',
              value: { id: defaultPersona.responseData.id, type: 'persona' },
            },
          ]);
        } finally {
          await afterAction();
        }
      }
    );

    test.afterAll(
      'Delete isolated term context fixtures',
      async ({ browser }) => {
        const { apiContext, afterAction } = await getDefaultAdminAPIContext(
          browser
        );
        try {
          await glossary.delete(apiContext);
          await Promise.all([
            defaultPersona.delete(apiContext),
            userPersona.delete(apiContext),
            user.delete(apiContext),
          ]);
        } finally {
          await afterAction();
        }
      }
    );

    test('maintainer saves, reloads and clears default and user-specific Personas', async ({
      adminPage: page,
    }) => {
      test.slow();
      await test.step('Configure both binding levels through the term tab', async () => {
        await openContext(page, editableTerm);
        await page.getByTestId('edit-term-persona-context').click();
        await page
          .getByRole('combobox', { name: 'Default Persona', exact: true })
          .fill(defaultPersona.data.name);
        await page
          .getByRole('option', {
            name: defaultPersona.data.displayName,
            exact: true,
          })
          .click();
        await page.getByTestId('add-context-override').click();
        const row = page.getByTestId('context-override-0');
        await row
          .getByRole('combobox', { name: 'User', exact: true })
          .fill(user.responseData.name);
        await page
          .getByRole('option', {
            name: user.responseData.displayName,
            exact: true,
          })
          .click();
        await row
          .getByRole('combobox', { name: 'Persona', exact: true })
          .fill(userPersona.data.name);
        await page
          .getByRole('option', {
            name: userPersona.data.displayName,
            exact: true,
          })
          .click();
        await saveContext(page, editableTerm);
        await expect(page.getByTestId('term-context-default')).toHaveText(
          defaultPersona.data.displayName
        );
        await expect(
          page.getByTestId(`term-context-user-${user.responseData.id}`)
        ).toContainText(userPersona.data.displayName);
      });
      await test.step('Bindings survive a reload and can both be removed', async () => {
        await page.reload();
        await waitForAllLoadersToDisappear(page);
        await expect(page.getByTestId('term-context-default')).toHaveText(
          defaultPersona.data.displayName
        );
        await page.getByTestId('edit-term-persona-context').click();
        await page.getByTestId('clear-default-context-persona').click();
        await page.getByTestId('remove-context-override-0').click();
        await saveContext(page, editableTerm);
        await page.reload();
        await waitForAllLoadersToDisappear(page);
        await expect(page.getByTestId('term-context-default')).toHaveText(
          'Not set'
        );
        await expect(
          page.getByTestId(`term-context-user-${user.responseData.id}`)
        ).toHaveCount(0);
      });
    });

    test('data consumer can read the context binding but cannot edit it', async ({
      dataConsumerPage: page,
    }) => {
      await test.step('Read the term configuration without editing controls', async () => {
        await openContext(page, readOnlyTerm);
        await expect(page.getByTestId('term-context-default')).toHaveText(
          defaultPersona.data.displayName
        );
        await expect(page.getByTestId('edit-term-persona-context')).toHaveCount(
          0
        );
      });
    });

    test('incomplete overrides and failed saves preserve the editor', async ({
      adminPage: page,
    }) => {
      await test.step('Incomplete user override displays validation', async () => {
        await openContext(page, validationTerm);
        await page.getByTestId('edit-term-persona-context').click();
        await page.getByTestId('add-context-override').click();
        await page.getByTestId('save-term-persona-context').click();
        await expect(page.getByRole('alert')).toContainText(
          'Select a user and a Persona for every override.'
        );
        await page.getByTestId('remove-context-override-0').click();
      });
      await test.step('A rejected update keeps the selected Persona for retry', async () => {
        await page
          .getByRole('combobox', { name: 'Default Persona', exact: true })
          .fill(defaultPersona.data.name);
        await page
          .getByRole('option', {
            name: defaultPersona.data.displayName,
            exact: true,
          })
          .click();
        await page.route(
          `**/api/v1/glossaryTerms/${validationTerm.responseData.id}`,
          async (route) => {
            if (route.request().method() === 'PATCH') {
              await route.fulfill({
                status: 403,
                contentType: 'application/json',
                body: JSON.stringify({ message: 'Context update denied' }),
              });
            } else {
              await route.continue();
            }
          }
        );
        const failedResponse = page.waitForResponse(
          (response) =>
            response.request().method() === 'PATCH' &&
            response.url().includes(validationTerm.responseData.id)
        );
        await page.getByTestId('save-term-persona-context').click();
        expect((await failedResponse).status()).toBe(403);
        await expect(
          page.getByTestId('save-term-persona-context')
        ).toBeEnabled();
        await expect(
          page
            .getByTestId('term-persona-context')
            .getByText(defaultPersona.data.displayName, { exact: true })
        ).toBeVisible();
      });
    });
  }
);
