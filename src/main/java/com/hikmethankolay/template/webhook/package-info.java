/**
 * Outbound webhooks: users register endpoints that receive signed HTTP POSTs (Standard Webhooks) when events happen.
 * Features publish events with {@link com.hikmethankolay.template.webhook.WebhookEvents}; deliveries go through an
 * outbox table and are retried with backoff.
 */
package com.hikmethankolay.template.webhook;
