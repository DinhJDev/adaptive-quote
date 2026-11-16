import { readFileSync } from "node:fs";
import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { parse } from "yaml";

const openapi = parse(readFileSync("../contracts/openapi.yaml", "utf8"));
const schemaId = "https://adaptive-quote.example/contracts/v1";
const ajv = new Ajv2020({ strict: false });
addFormats(ajv);
ajv.addSchema({ $id: schemaId, components: openapi.components });
const validate = (name: string) => ajv.compile({ $ref: schemaId + "#/components/schemas/" + name });

test("a typed vehicle reaches a Spring-priced tune with an opt-in add-on", async ({ page }) => {
  await page.goto("/");
  const extraction = page.waitForResponse(
    (response) => response.url().endsWith("/api/v1/extractions") && response.ok(),
  );
  await submitDescription(page, "2022 86 with GR intake");
  const extracted = await extraction;
  expect(validate("ExtractionRequest")(extracted.request().postDataJSON())).toBe(true);
  const extractedBody = await extracted.json();
  expect(validate("Extraction")(extractedBody)).toBe(true);
  expect(extractedBody.match.status).toBe("matched");
  await expect(page.getByRole("heading", { name: "Confirm your modifications" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Intake", exact: true })).toHaveAttribute("aria-pressed", "true");
  await confirmContact(page);
  await page.getByRole("radio", { name: /Stage 1 calibration tune/ }).check();
  const addOn = page.getByRole("checkbox", { name: /Data log review/ });
  await expect(addOn).not.toBeChecked();
  await expect(page.locator(".total-value strong")).toHaveText("$399.00");

  const preview = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/quotes/preview")
    && response.ok()
    && response.request().postDataJSON().addOnIds.includes("log-review"),
  );
  await addOn.check();
  const priced = await preview;
  expect(validate("PreviewRequest")(priced.request().postDataJSON())).toBe(true);
  const pricedBody = await priced.json();
  expect(validate("Preview")(pricedBody)).toBe(true);
  expect(pricedBody.totalMinor).toBe(44_800);
  await expect(page.locator(".total-value strong")).toHaveText("$448.00");
  await addOn.uncheck();
  await expect(page.locator(".total-value strong")).toHaveText("$399.00");
});

test("2021 Toyota 86 goes straight to modification review and editing resets selections", async ({ page }) => {
  await page.goto("/");
  await submitDescription(page, "2021 86 with BC Coilovers & Apex'i Single Exit Exhaust");
  await expect(page.getByRole("heading", { name: "Confirm your modifications" })).toBeFocused();
  await expect(page.getByText("2021 Toyota GT86", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Exhaust", exact: true })).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByRole("button", { name: "Cosmetic or chassis changes" })).toHaveAttribute("aria-pressed", "true");
  await confirmContact(page);
  await page.getByRole("radio", { name: /Stage 1 calibration tune/ }).check();
  await expect(page.locator(".total-value strong")).toHaveText("$399.00");

  await page.getByRole("button", { name: "Edit vehicle" }).click();
  await page.getByRole("button", { name: "2022 Ford Mustang GT" }).click();
  await expect(page.getByRole("button", { name: "Exhaust", exact: true })).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "Unsure", exact: true }).click();
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByRole("radio", { name: /Vehicle assessment/ })).toBeVisible();
  await expect(page.getByRole("radio", { name: /Stage 1 calibration tune/ })).toHaveCount(0);
  await page.getByRole("radio", { name: /Vehicle assessment/ }).check();
  await expect(page.locator(".total-value strong")).toHaveText("$99.00");
});

test("typed parts and a quick tag reach categorized details and a switchable recommendation", async ({ page, context, browser }) => {
  await page.goto("/");
  await submitDescription(page, "2022 86");
  await page.getByRole("textbox", { name: "Installed parts" }).fill("BC Coilovers, Apex’i Single Exit Exhaust");
  await page.getByRole("button", { name: "Intake", exact: true }).click();
  const classified = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/extractions")
    && response.request().postDataJSON().scope === "modifications" && response.ok());
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  const response = await classified;
  expect(validate("ExtractionRequest")(response.request().postDataJSON())).toBe(true);
  expect(validate("Extraction")(await response.json())).toBe(true);
  const performance = page.getByRole("region", { name: "Performance", exact: true });
  await expect(performance.getByText("Apex’i Single Exit Exhaust", { exact: true })).toBeVisible();
  await expect(performance.getByText("Intake", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Miscellaneous", exact: true })
    .getByText("BC Coilovers", { exact: true })).toBeVisible();
  await expect(page.getByText("Alex Demo", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /Continue to quote/ }).click();
  await expect(page.getByRole("heading", { name: "Your quote is ready" })).toBeVisible();
  await expect(page.getByText(/Recommended · Stage 2/)).toBeVisible();
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  const created = page.waitForResponse((response) =>
    response.url().endsWith("/api/v1/quote-links") && response.request().method() === "POST");
  await page.getByRole("button", { name: "Copy quote link" }).click();
  const createdLink = await created;
  expect(createdLink.status()).toBe(201);
  expect(validate("QuoteLink")(await createdLink.json())).toBe(true);
  await expect(page.getByRole("status").filter({ hasText: "Quote link copied." })).toBeVisible();
  const url = await page.evaluate(() => navigator.clipboard.readText());
  await expect(page.getByRole("textbox", { name: "Quote link" })).toHaveValue(url);
  expect([...new URL(url).searchParams.keys()]).toEqual(["quote"]);
  expect(new URL(url).searchParams.get("quote")).toMatch(/^[0-9a-f-]{36}$/);

  const otherBrowser = await browser.newContext();
  const reopened = await otherBrowser.newPage();
  let extractions = 0;
  reopened.on("request", (request) => {
    if (request.url().endsWith("/api/v1/extractions")) extractions++;
  });
  await reopened.goto(url);
  const summary = reopened.getByRole("region", { name: "Your quote is ready" });
  await expect(summary.getByRole("heading", { name: "2022 Toyota GR86" })).toBeVisible();
  await expect(summary.getByText("Apex’i Single Exit Exhaust", { exact: true })).toBeVisible();
  await expect(summary.getByText("BC Coilovers", { exact: true })).toBeVisible();
  await expect(summary.getByText(/Recommended · Stage 2/)).toBeVisible();
  await reopened.reload();
  await expect(summary.getByText("Apex’i Single Exit Exhaust", { exact: true })).toBeVisible();
  await reopened.getByRole("button", { name: "See your quote", exact: true }).click();
  await expect(reopened.locator(".total-value strong")).toHaveText("$524.00");
  expect(extractions).toBe(0);
  await otherBrowser.close();

  await page.getByRole("button", { name: "See your quote", exact: true }).click();
  await expect(page.getByRole("radio", { name: /Stage 2 calibration tune/ })).toBeChecked();
  await expect(page.locator(".total-value strong")).toHaveText("$524.00");
  await page.getByRole("radio", { name: /Stage 3 calibration tune/ }).check();
  await expect(page.locator(".total-value strong")).toHaveText("$649.00");
  await page.getByRole("radio", { name: /Stage 1 calibration tune/ }).check();
  await expect(page.locator(".total-value strong")).toHaveText("$399.00");
});

test("a live intake fallback is labeled and still reaches a priced review", async ({ page }) => {
  await page.route("**/api/v1/catalog", async (route) => {
    const response = await route.fetch();
    await route.fulfill({ response, json: { ...await response.json(), liveEnabled: true } });
  });
  await page.goto("/embedded");
  await expect(page.getByRole("button", { name: "Continue", exact: true })).toBeEnabled();
  await expect(page.getByText("Descriptions may be sent to OpenAI.", { exact: false })).toHaveCount(0);
  const extraction = page.waitForResponse((response) => response.url().endsWith("/api/v1/extractions") && response.ok());
  await submitDescription(page, "2022 86 with GR intake");
  const response = await extraction;
  expect(response.request().postDataJSON()).toMatchObject({ mode: "live", text: "2022 86 with GR intake" });
  expect(validate("ExtractionRequest")(response.request().postDataJSON())).toBe(true);
  const result = await response.json();
  expect(validate("Extraction")(result)).toBe(true);
  expect(result).toMatchObject({ source: "catalog", fallbackReason: "unavailable" });
  await expect(page.getByText("Live extraction is unavailable. We checked the demo catalog.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Intake", exact: true })).toHaveAttribute("aria-pressed", "true");
  await confirmContact(page);
  await page.getByRole("radio", { name: /Stage 1 calibration tune/ }).check();
  await expect(page.locator(".total-value strong")).toHaveText("$399.00");
});

test("a Toyota 86 without a year offers a focused vehicle and year selection", async ({ page }) => {
  await page.goto("/");
  await submitDescription(page, "86 with an exhaust");
  const choices = page.getByRole("combobox", { name: "Vehicle and year" });
  await expect(choices).toBeVisible();
  await expect(choices.getByRole("option", { name: "2020 Volkswagen Golf GTI" })).toHaveCount(0);
  await choices.selectOption("toyota-gt86-2017");
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByText("2017 Toyota GT86", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Exhaust", exact: true })).toHaveAttribute("aria-pressed", "true");
});

test("header links are accessible and the brand confirms before discarding progress", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("link", { name: "GitHub repository" }))
    .toHaveAttribute("href", "https://github.com/DinhJDev/adaptive-quote");
  await expect(page.getByRole("link", { name: "Portfolio" })).toHaveAttribute("href", "https://dinhjdev.com/");

  const brand = page.getByRole("button", { name: "Adaptive Quote", exact: true });
  const dialog = page.getByRole("alertdialog", { name: "Discard your progress?" });
  const description = page.getByRole("textbox", { name: "Vehicle description" });
  await brand.click();
  await expect(dialog).toHaveCount(0);

  await description.fill("2022 86 with GR intake");
  await brand.click();
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Keep editing" })).toBeFocused();
  await dialog.getByRole("button", { name: "Keep editing" }).click();
  await expect(description).toHaveValue("2022 86 with GR intake");

  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Confirm your modifications" })).toBeVisible();
  await brand.click();
  await dialog.getByRole("button", { name: "Discard and restart" }).click();
  await expect(page.getByRole("heading", { name: "What are you driving?" })).toBeFocused();
  await expect(description).toHaveValue("");
});

test("manual intake can reach a priced review by keyboard on mobile", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/embedded");
  await expect(page.getByRole("heading", { name: "Performance tuning" })).toBeVisible();
  await page.getByRole("button", { name: "Enter vehicle manually" }).press("Enter");
  await page.getByRole("combobox", { name: "Vehicle", exact: true }).selectOption("ford-mustang-gt-2022");
  await page.getByRole("button", { name: "Continue", exact: true }).press("Enter");
  await page.getByRole("button", { name: "Stock", exact: true }).press("Space");
  await page.getByRole("button", { name: "Continue", exact: true }).press("Enter");
  await page.getByRole("button", { name: /Continue to quote/ }).press("Enter");
  const openQuote = page.getByRole("button", { name: "See your quote" });
  await expect(openQuote).toBeEnabled();
  await openQuote.press("Enter");
  await page.getByRole("radio", { name: /Vehicle assessment/ }).press("Space");
  await expect(page.locator(".total-value strong")).toHaveText("$99.00");
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
});

test("a classic outside the catalog reaches custom pricing and offers follow-up by call", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Enter vehicle manually" }).click();
  await page.getByRole("combobox", { name: "Vehicle", exact: true }).selectOption("enter-details");
  await page.getByLabel("Make", { exact: true }).selectOption("Ford");
  await page.getByLabel("Model", { exact: true }).fill("Model A");
  await page.getByLabel("Year", { exact: true }).fill("1930");
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByText("1930 Ford Model A", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Stock", exact: true }).click();
  await confirmContact(page);
  await expect(page.getByRole("heading", { name: "Custom pricing" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Save quote" })).toHaveCount(0);
  await page.getByRole("radio", { name: "Phone call" }).check();
  await page.getByRole("button", { name: "Preview tuner follow-up" }).click();
  await expect(page.getByRole("status")).toHaveText(/follow up by phone/);
  await page.reload();
  await page.getByRole("button", { name: "Resume saved draft" }).click();
  await expect(page.getByRole("heading", { name: "Custom pricing" })).toBeVisible();
  await expect(page.getByText("1930 Ford Model A", { exact: true })).toBeVisible();
});

for (const model of ["X5", "335i Touring"]) {
  test(`an unverified extraction can be edited to ${model} and keeps detected mods`, async ({ page }) => {
    const description = "2008 BMW 335i with an aftermarket muffler";
    await page.route("**/api/v1/extractions", async (route) => {
      if (route.request().postDataJSON().text !== description) return route.continue();
      const manual = await page.request.post("/api/v1/extractions", {
        data: { mode: "manual", vehicle: { make: "BMW", model: "335i", year: 2008 } },
      });
      const result = await manual.json();
      await route.fulfill({ json: {
        ...result,
        source: "live",
        facts: { modIds: ["exhaust"], stockMention: false },
        match: { ...result.match, status: "unverified" },
      } });
    });
    await page.goto("/");
    await submitDescription(page, description);
    await expect(page.getByRole("button", { name: /2008 BMW 335i/ })).toBeVisible();
    await expect(page.getByText("Model unverified")).toBeVisible();
    await page.getByText("Edit vehicle details", { exact: true }).click();
    await expect(page.getByLabel("Make", { exact: true })).toHaveValue("BMW");
    await expect(page.getByLabel("Model", { exact: true })).toHaveValue("335i");
    await expect(page.getByLabel("Year", { exact: true })).toHaveValue("2008");
    await page.getByLabel("Model", { exact: true }).fill(model);
    await page.getByRole("button", { name: "Confirm vehicle" }).click();
    await expect(page.getByRole("button", { name: "Exhaust", exact: true })).toHaveAttribute("aria-pressed", "true");
    await confirmContact(page);
    if (model === "X5") {
      await page.getByRole("radio", { name: /Stage 1 calibration tune/ }).check();
      await expect(page.locator(".total-value strong")).toHaveText("$574.00");
    } else {
      await expect(page.getByRole("heading", { name: "Custom pricing" })).toBeVisible();
      await expect(page.getByRole("button", { name: "Save quote" })).toHaveCount(0);
    }
  });
}

test("contract example matches the published schemas", () => {
  const post = openapi.paths["/api/v1/quotes/preview"].post;
  expect(validate("PreviewRequest")(post.requestBody.content["application/json"].example)).toBe(true);
  expect(validate("Preview")(post.responses[200].content["application/json"].example)).toBe(true);
});

for (const path of ["/", "/embedded"]) {
  test("one primary action and secondary examples at " + path, async ({ page }) => {
    await page.goto(path);
    const form = page.locator(".intake-form");
    const input = form.getByPlaceholder("e.g. 2022 Toyota GR86 with GR intake", { exact: true });
    await expect(input).toBeVisible();
    await expect(form.locator('button[data-variant="default"]')).toHaveCount(1);
    await expect(form.getByRole("button", { name: "2020 VW GTI", exact: true })).toBeHidden();
    await form.getByRole("button", { name: "Use an example" }).click();
    for (const example of ["2020 VW GTI", "2000 Nissan GTR", "2022 Ford Mustang GT"]) {
      await expect(form.getByRole("button", { name: example, exact: true })).toBeVisible();
    }
    await form.getByRole("button", { name: "2020 VW GTI", exact: true }).click();
    await expect(input).toHaveValue("2020 VW GTI");
    await expect(form.getByRole("button", { name: "Continue" })).toBeVisible();
    const extraction = page.waitForResponse((response) => response.url().endsWith("/api/v1/extractions") && response.ok());
    await input.press("Enter");
    const facts = await (await extraction).json();
    expect(facts.match.candidates[0]).toMatchObject({ make: "Volkswagen", model: "Golf GTI", year: 2020 });
    await expect(page.getByText("2020 Volkswagen Golf GTI", { exact: true })).toBeVisible();

    await page.goBack();
    await expect(input).toHaveValue("2020 VW GTI");
    await page.goForward();
    await expect(page.getByRole("heading", { name: "Confirm your modifications" })).toBeVisible();
  });
}

test("browser Back and Forward preserve answers and recheck a changed quote", async ({ page }) => {
  await quoteWithAddOn(page);
  const length = await page.evaluate(() => history.length);
  let saveRequests = 0;
  page.on("request", (request) => { if (request.method() === "PUT") saveRequests += 1; });

  await page.goBack();
  await expect(page.getByRole("heading", { name: "Your quote is ready" })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("heading", { name: "Review your build" })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("button", { name: "Intake", exact: true })).toHaveAttribute("aria-pressed", "true");
  await page.goForward();
  await expect(page.getByRole("heading", { name: "Review your build" })).toBeVisible();
  await page.goForward();
  await expect(page.getByRole("heading", { name: "Your quote is ready" })).toBeVisible();
  await page.goForward();
  await expect(page.locator(".total-value strong")).toHaveText("$448.00");
  await expect(page.getByRole("checkbox", { name: /Data log review/ })).toBeChecked();
  expect(await page.evaluate(() => history.length)).toBe(length);

  await page.goBack();
  await expect(page.getByRole("heading", { name: "Your quote is ready" })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("heading", { name: "Review your build" })).toBeVisible();
  await page.goBack();
  await page.getByRole("button", { name: "Stock", exact: true }).click();
  await page.goForward();
  await expect(page.getByRole("heading", { name: "Confirm your modifications" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Intake", exact: true })).toHaveAttribute("aria-pressed", "false");
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await expect(page.getByRole("radio", { name: /Stage 1 calibration tune/ })).not.toBeChecked();
  expect(saveRequests).toBe(0);
});

test("a draft resumes after reload and a saved receipt reopens with its original total", async ({ page }) => {
  await quoteWithAddOn(page);
  await page.reload();
  await page.getByRole("button", { name: "Resume saved draft" }).click();
  await expect(page.getByRole("checkbox", { name: /Data log review/ })).toBeChecked();
  await expect(page.locator(".total-value strong")).toHaveText("$448.00");

  const response = page.waitForResponse((result) => result.request().method() === "PUT" && result.status() === 201);
  await confirmSave(page);
  const created = await response;
  expect(validate("SaveQuoteRequest")(created.request().postDataJSON())).toBe(true);
  const receipt = await created.json();
  expect(validate("QuoteReceipt")(receipt)).toBe(true);
  await expect(page.getByRole("heading", { name: "Quote saved" })).toBeVisible();

  await page.reload();
  const opened = page.waitForResponse((result) => result.url().endsWith("/api/v1/quotes/" + receipt.quoteId));
  await page.getByRole("button", { name: "Reopen saved quote" }).click();
  expect(await (await opened).json()).toEqual(receipt);
  await expect(page.locator(".total-value strong")).toHaveText("$448.00");
  await page.getByText("Example checkout request", { exact: true }).click();
  await expect(page.locator("pre")).toContainText('"unit_amount": 4900');
});

test("retry after a committed save loses its response and reuses the same ID after reload", async ({ page }) => {
  await quoteWithAddOn(page);
  let first: { url: string; body: unknown; receipt: unknown } | undefined;
  await page.route("**/api/v1/quotes/*", async (route) => {
    const result = await route.fetch();
    expect(result.status()).toBe(201);
    first = { url: route.request().url(), body: route.request().postDataJSON(), receipt: await result.json() };
    await route.abort("failed");
  }, { times: 1 });
  await confirmSave(page);
  await expect(page.getByRole("button", { name: "Retry save" })).toBeVisible();
  expect(first).toBeDefined();

  await page.reload();
  await page.getByRole("button", { name: "Resume pending save" }).click();
  const retried = page.waitForResponse((result) => result.request().method() === "PUT");
  await page.getByRole("button", { name: "Retry save" }).click();
  const response = await retried;
  expect(response.status()).toBe(200);
  expect(response.url()).toBe(first?.url);
  expect(response.request().postDataJSON()).toEqual(first?.body);
  expect(await response.json()).toEqual(first?.receipt);
  await expect(page.getByRole("heading", { name: "Quote saved" })).toBeVisible();
});

async function confirmContact(page: Page) {
  await page.getByRole("button", { name: "Continue", exact: true }).click();
  await page.getByRole("button", { name: /Continue to quote/ }).click();
  await page.getByRole("button", { name: "See your quote" }).click();
}

async function quoteWithAddOn(page: Page) {
  await page.goto("/");
  await submitDescription(page, "2022 Toyota GR86 with GR intake");
  await confirmContact(page);
  await page.getByRole("radio", { name: /Stage 1 calibration tune/ }).check();
  await page.getByRole("checkbox", { name: /Data log review/ }).check();
  await expect(page.locator(".total-value strong")).toHaveText("$448.00");
}

async function submitDescription(page: Page, value: string) {
  await page.getByRole("textbox", { name: "Vehicle description" }).fill(value);
  await page.getByRole("button", { name: "Continue", exact: true }).click();
}

async function confirmSave(page: Page) {
  await page.getByRole("checkbox", { name: "Confirm these demo prices" }).check();
  await page.getByRole("button", { name: "Save quote", exact: true }).click();
}
