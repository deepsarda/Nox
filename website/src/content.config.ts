import { defineCollection } from 'astro:content';
import { z } from 'astro:schema';
import { docsLoader } from '@astrojs/starlight/loaders';
import { docsSchema } from '@astrojs/starlight/schema';
import { glob } from 'astro/loaders';

const blogSchema = z.object({
  title: z.string(),
  date: z.date(),
  description: z.string().optional(),
  author: z.string().default('Nox Team'),
  tags: z.array(z.string()).default([]),
});

export const collections = {
  docs: defineCollection({ loader: docsLoader(), schema: docsSchema() }),
  blog: defineCollection({
    loader: glob({ pattern: "**/*.md", base: "./src/content/blog" }),
    schema: blogSchema,
  }),
};
