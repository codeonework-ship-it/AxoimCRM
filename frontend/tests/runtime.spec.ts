import { expect, Page, test } from '@playwright/test'

async function signIn(page: Page) {
  const email = process.env.AXIOM_E2E_EMAIL
  const password = process.env.AXIOM_E2E_PASSWORD
  test.skip(!email || !password, 'AXIOM_E2E_EMAIL and AXIOM_E2E_PASSWORD are required')
  await page.goto('/login')
  await page.locator('#sso-email').fill(email!)
  await page.getByRole('button', { name: 'Sign in with credentials', exact: true }).click()
  await page.locator('#c-password').fill(password!)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/)
}

async function loadScreen(page: Page) {
  const load = page.getByRole('button', { name: 'Load Grid Data' }).first()
  try {
    await load.waitFor({ state: 'visible', timeout: 2500 })
    await load.click()
  } catch {
    // Screens without a data grid intentionally have no load interaction.
  }
}

test('authenticated P0 routes render without uncaught runtime failures', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('console', message => {
    if (message.type() === 'error' && !message.text().includes('favicon')) errors.push(message.text())
  })
  await signIn(page)
  for (const path of ['/', '/accounts', '/leads', '/pipeline', '/reports', '/admin', '/audit', '/mobile', '/packs/bfsi', '/packs/commodity']) {
    await page.goto(path)
    await loadScreen(page)
    await expect(page.getByRole('main')).toBeVisible()
  }
  expect(errors, 'No route may raise an uncaught error or error-level console message').toEqual([])
})

test('record authoring modal has a spacious aligned control grid', async ({ page }) => {
  await signIn(page)
  await page.goto('/contacts')
  await page.getByRole('button', { name: 'New contact', exact: true }).click()

  const dialog = page.getByRole('dialog', { name: 'New contact', exact: true })
  await expect(dialog).toBeVisible()
  const geometry = await dialog.evaluate(element => {
    const dialogRect = element.getBoundingClientRect()
    const fields = [...element.querySelectorAll('.record-form-grid .field')]
    const controls = fields.map(field => {
      const fieldRect = field.getBoundingClientRect()
      const controlRect = field.querySelector('input, select, textarea')!.getBoundingClientRect()
      return {
        fieldWidth: fieldRect.width,
        controlWidth: controlRect.width,
        controlHeight: controlRect.height,
        controlY: controlRect.y,
      }
    })
    const actions = [...element.querySelectorAll('.record-form-actions .btn')]
      .map(action => action.getBoundingClientRect().width)
    const scrim = element.closest('.record-scrim')
    return {
      width: dialogRect.width,
      height: dialogRect.height,
      controls,
      actions,
      scrimIsBodyChild: scrim?.parentElement === document.body,
      scrimPosition: scrim ? getComputedStyle(scrim).position : null,
    }
  })

  expect(geometry.scrimIsBodyChild).toBe(true)
  expect(geometry.scrimPosition).toBe('fixed')
  expect(geometry.width).toBeGreaterThanOrEqual(900)
  expect(geometry.height).toBeGreaterThanOrEqual(600)
  expect(geometry.controls).toHaveLength(9)
  for (const control of geometry.controls) {
    expect(Math.abs(control.fieldWidth - control.controlWidth)).toBeLessThanOrEqual(1)
    expect(control.controlHeight).toBeGreaterThanOrEqual(44)
  }
  expect(new Set(geometry.controls.slice(0, 3).map(control => control.controlY)).size).toBe(1)
  expect(new Set(geometry.controls.slice(3, 6).map(control => control.controlY)).size).toBe(1)
  expect(new Set(geometry.controls.slice(6, 9).map(control => control.controlY)).size).toBe(1)
  expect(Math.abs(geometry.actions[0] - geometry.actions[1])).toBeLessThanOrEqual(1)
})

test('BFSI and Commodity operations are separated into accessible route tabs', async ({ page }) => {
  await signIn(page)
  await page.goto('/packs/bfsi')
  await loadScreen(page)

  const industryTabs = page.getByRole('tablist', { name: 'Industry workflow', exact: true })
  await expect(industryTabs).toBeVisible()
  await expect(industryTabs.getByRole('tab', { name: 'BFSI Onboarding, screening and exceptions', exact: true })).toHaveAttribute('aria-selected', 'true')
  const commodityTab = industryTabs.getByRole('tab', { name: 'Commodity Pricing, approval and execution', exact: true })
  await commodityTab.click()

  await expect(page).toHaveURL(/\/packs\/commodity$/)
  await loadScreen(page)
  await expect(page.getByRole('tablist', { name: 'Industry workflow', exact: true })
    .getByRole('tab', { name: 'Commodity Pricing, approval and execution', exact: true })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.release-control-head h2')).toContainText('Commodity Pricing, Approval And Execution')
})

test('core list APIs enforce the 100-row server page contract', async ({ page }) => {
  await signIn(page)
  const result = await page.evaluate(async () => {
    const stored = localStorage.getItem('axiom.session')
    const token = stored ? JSON.parse(stored).token as string | undefined : undefined
    const headers = token ? { Authorization: `Bearer ${token}` } : {}
    const paths = ['/api/v1/accounts?page=0', '/api/v1/leads?page=0', '/api/v1/opportunities?page=0']
    return Promise.all(paths.map(async path => {
      const response = await fetch(`http://localhost:8080${path}`, { headers })
      const contentType = response.headers.get('content-type') ?? ''
      const body = contentType.includes('application/json') ? await response.json() : {}
      return { path, status: response.status, size: body.size, contentType }
    }))
  })
  for (const item of result) {
    expect(item.status, item.path).toBe(200)
    expect(item.size, item.path).toBe(100)
  }
})

test('the page renders immediately and grid data waits for its grid Load action', async ({ page }) => {
  await signIn(page)
  const accountRequests: string[] = []
  page.on('request', request => {
    if (/\/api\/v1\/accounts(?:\?|$)/.test(request.url())) accountRequests.push(request.url())
  })

  await page.goto('/accounts')
  await expect(page.getByRole('heading', { name: 'Accounts', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Load Grid Data' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Load Screen Data' })).toHaveCount(0)
  await page.waitForTimeout(400)
  expect(accountRequests, 'No account data call is allowed before the explicit load action').toEqual([])

  await page.getByRole('button', { name: 'Load Grid Data' }).click()
  await expect.poll(() => accountRequests.length, { message: 'Loading the grid must start its tenant-scoped query' }).toBeGreaterThan(0)
  expect(accountRequests.some(url => /(?:\?|&)page=0(?:&|$)/.test(url))).toBeTruthy()
})

test('hidden administration tabs do not load until selected', async ({ page }) => {
  await signIn(page)
  const alertRequests: string[] = []
  page.on('request', request => {
    if (/\/api\/v1\/alerts\/(?:email|reports)(?:\?|$)/.test(request.url())) alertRequests.push(request.url())
  })

  await page.goto('/admin/users')
  await loadScreen(page)
  await expect(page.getByRole('tab', { name: 'users', exact: true })).toHaveAttribute('aria-selected', 'true')
  await page.waitForTimeout(400)
  expect(alertRequests, 'A hidden tab must not consume API or database capacity').toEqual([])

  await page.getByRole('tab', { name: 'alerts', exact: true }).click()
  await page.getByRole('button', { name: 'Load Grid Data' }).click()
  await expect.poll(() => alertRequests.length).toBeGreaterThanOrEqual(2)
})

test('grid audit opens as an unclipped viewport drawer', async ({ page }) => {
  await signIn(page)
  await page.goto('/campaigns')
  await loadScreen(page)
  await page.getByRole('button', { name: 'Audit', exact: true }).click()

  const drawer = page.locator('body > .audit-drawer-scrim > .audit-drawer')
  await expect(drawer).toBeVisible()
  const geometry = await drawer.evaluate(element => {
    const drawerRect = element.getBoundingClientRect()
    const headerRect = element.querySelector('.drawer-head')!.getBoundingClientRect()
    const listRect = element.querySelector('.audit-list')!.getBoundingClientRect()
    return {
      top: drawerRect.top,
      bottom: drawerRect.bottom,
      viewportHeight: window.innerHeight,
      listTop: listRect.top,
      headerBottom: headerRect.bottom,
      bodyOverflow: document.body.style.overflow,
    }
  })
  expect(geometry.top).toBeLessThanOrEqual(1)
  expect(Math.abs(geometry.bottom - geometry.viewportHeight)).toBeLessThanOrEqual(1)
  expect(geometry.listTop).toBeGreaterThanOrEqual(geometry.headerBottom - 1)
  expect(geometry.bodyOverflow).toBe('hidden')

  await page.keyboard.press('Escape')
  await expect(drawer).toBeHidden()
})

test('Account 360 is a usable dock and grid actions render as controls', async ({ page }) => {
  await signIn(page)
  await page.goto('/accounts')
  await loadScreen(page)

  const viewButtons = page.getByRole('button', { name: 'View 360', exact: true })
  await expect(viewButtons.first()).toBeVisible()
  const viewButtonCount = await viewButtons.count()
  expect(viewButtonCount, 'Seeded accounts must expose at least one Account 360 action').toBeGreaterThan(0)

  const firstViewButton = viewButtons.first()
  const actionStyle = await firstViewButton.evaluate(element => {
    const style = getComputedStyle(element)
    return {
      borderStyle: style.borderStyle,
      height: element.getBoundingClientRect().height,
      whiteSpace: style.whiteSpace,
    }
  })
  expect(actionStyle.borderStyle).not.toBe('none')
  expect(actionStyle.height).toBeGreaterThanOrEqual(32)
  expect(actionStyle.whiteSpace).toBe('nowrap')

  await firstViewButton.click()
  const drawer = page.getByRole('dialog', { name: 'Account 360', exact: true })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByRole('button', { name: 'Close Account 360', exact: true })).toBeVisible()

  await drawer.getByRole('button', { name: 'Full View', exact: true }).click()
  await expect(drawer).toHaveClass(/account-360-drawer-full/)
  await drawer.getByRole('button', { name: 'Restore View', exact: true }).click()
  await expect(drawer).not.toHaveClass(/account-360-drawer-full/)

  const factorList = drawer.locator('.audit-event > .health-factor-list')
  await expect(factorList).toBeVisible()
  expect(await factorList.evaluate(element => getComputedStyle(element).gridTemplateColumns)).not.toContain(' ')

  await drawer.getByRole('button', { name: 'Close Account 360', exact: true }).click()
  await expect(drawer).toBeHidden()
})

test('governed master values expose complete CRUD with soft-delete protection', async ({ page }) => {
  await signIn(page)
  const result = await page.evaluate(async () => {
    const stored = localStorage.getItem('axiom.session')
    const token = stored ? JSON.parse(stored).token as string | undefined : undefined
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
    const base = 'http://localhost:8080/api/v1/reference'
    const setsResponse = await fetch(`${base}/value-sets`, { headers })
    const sets = await setsResponse.json() as Array<{ apiName: string; active: boolean }>
    const set = sets.find(item => item.active)
    if (!set) return { ok: false, step: 'no-active-master', set: '', code: '' }

    const code = 'E2E_CRUD_VALUE'
    const entriesResponse = await fetch(`${base}/value-sets/${set.apiName}/entries?includeInactive=true`, { headers })
    const entries = await entriesResponse.json() as Array<{ code: string; sortOrder: number }>
    const existing = entries.find(item => item.code === code)
    const body = { code, label: 'E2E Master Value', sortOrder: existing?.sortOrder ?? 9870, active: true, effectiveFrom: null, effectiveTo: null }
    const createOrRestore = await fetch(`${base}/value-sets/${set.apiName}/entries${existing ? `/${code}` : ''}`, {
      method: existing ? 'PATCH' : 'POST', headers, body: JSON.stringify(body),
    })
    if (!createOrRestore.ok) return { ok: false, step: 'create-or-restore', status: createOrRestore.status, set: set.apiName, code }

    const update = await fetch(`${base}/value-sets/${set.apiName}/entries/${code}`, {
      method: 'PATCH', headers, body: JSON.stringify({ ...body, label: 'E2E Master Value Updated' }),
    })
    if (!update.ok) return { ok: false, step: 'update', status: update.status, set: set.apiName, code }

    const remove = await fetch(`${base}/value-sets/${set.apiName}/entries/${code}?reason=${encodeURIComponent('Automated CRUD verification')}`, {
      method: 'DELETE', headers,
    })
    if (!remove.ok) return { ok: false, step: 'soft-delete', status: remove.status, set: set.apiName, code }
    const retired = await remove.json() as { active: boolean }
    return { ok: retired.active === false, step: 'complete', set: set.apiName, code }
  })

  expect(result).toMatchObject({ ok: true, step: 'complete' })
  await page.goto(`/reference-data/${result.set}`)
  await loadScreen(page)
  await expect(page.getByRole('button', { name: 'New Value', exact: true })).toBeVisible()
  await expect(page.getByLabel('Reference code', { exact: true })).toBeVisible()
  const row = page.getByRole('row').filter({ hasText: result.code })
  await expect(row).toBeVisible()
  await expect(row.getByRole('button', { name: 'Edit', exact: true })).toBeVisible()
  await expect(row.getByRole('button', { name: 'Clone', exact: true })).toBeVisible()
  await expect(row.getByRole('button', { name: 'Restore', exact: true })).toBeVisible()
})
