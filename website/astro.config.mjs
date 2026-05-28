// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

import tailwindcss from '@tailwindcss/vite';
import react from '@astrojs/react';

// https://astro.build/config
export default defineConfig({
  integrations: [
    starlight({
      title: 'Nox',
      expressiveCode: {
        themes: ['github-dark', 'github-light'],
        styleOverrides: {
          frames: {
            frameBoxShadowCssValue: 'none',
          },
          colors: {
            'editor.background': '#09090b',
            'terminal.background': '#09090b',
          }
        }
      },
      customCss: [
        './src/styles/global.css',
      ],
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/deepsarda/Nox' }],
      sidebar: [
        {
          label: 'Guides',
          autogenerate: { directory: 'guides' },
        },
        {
          label: 'Architecture',
          autogenerate: { directory: 'docs/architecture' },
        },
        {
          label: 'Compiler',
          autogenerate: { directory: 'docs/compiler' },
        },
        {
          label: 'Language',
          autogenerate: { directory: 'docs/language' },
        },
        {
          label: 'Reference',
          autogenerate: { directory: 'docs/reference' },
        },
        {
          label: 'Virtual Machine',
          autogenerate: { directory: 'docs/vm' },
        }
      ],
    }),
    react()
  ],

  vite: {
    plugins: [tailwindcss()],
  },
});