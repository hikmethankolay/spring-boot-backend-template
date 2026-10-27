import { defineCollection } from 'astro:content';
import { docsLoader, i18nLoader } from '@astrojs/starlight/loaders';
import { docsSchema, i18nSchema } from '@astrojs/starlight/schema';

export const collections = {
  docs: defineCollection({ loader: docsLoader(), schema: docsSchema() }),
  // Starlight reads this collection for UI strings; declaring it (with an empty en.json) silences its build warning.
  i18n: defineCollection({ loader: i18nLoader(), schema: i18nSchema() }),
};
