import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const docsSrc = path.join(__dirname, '../docs');
const guidesSrc = path.join(__dirname, '../guides');
const docsDest = path.join(__dirname, 'src/content/docs/docs');
const guidesDest = path.join(__dirname, 'src/content/docs/guides');

function copyDir(src, dest) {
  if (!fs.existsSync(src)) return;
  fs.mkdirSync(dest, { recursive: true });
  const entries = fs.readdirSync(src, { withFileTypes: true });

  for (const entry of entries) {
    const srcPath = path.join(src, entry.name);
    const destPath = path.join(dest, entry.name);

    if (entry.isDirectory()) {
      copyDir(srcPath, destPath);
    } else {
      if (srcPath.endsWith('.md')) {
        let content = fs.readFileSync(srcPath, 'utf8');
        // Convert nox codeblocks to rust for syntax highlighting
        content = content.replace(/```nox\n/g, '```rust\n');
        
        // Check if has frontmatter
        if (!content.startsWith('---\n')) {
          // Extract first heading
          const match = content.match(/^#\s+(.+)$/m);
          const title = match ? match[1] : entry.name.replace('.md', '');
          const escapedTitle = title.replace(/"/g, '\\"');
          const frontmatter = `---\ntitle: "${escapedTitle}"\n---\n\n`;
          fs.writeFileSync(destPath, frontmatter + content);
        } else {
          fs.writeFileSync(destPath, content);
        }
      } else {
        fs.copyFileSync(srcPath, destPath);
      }
    }
  }
}

console.log("Syncing documentation and injecting frontmatter...");
// Clear existing target dirs to avoid stale files
if (fs.existsSync(docsDest)) fs.rmSync(docsDest, { recursive: true, force: true });
if (fs.existsSync(guidesDest)) fs.rmSync(guidesDest, { recursive: true, force: true });

copyDir(docsSrc, docsDest);
copyDir(guidesSrc, guidesDest);

// Sync install scripts
const installShSrc = path.join(__dirname, '../bin/install.sh');
const installShDest = path.join(__dirname, 'public/install.sh');
const installPs1Src = path.join(__dirname, '../bin/install.ps1');
const installPs1Dest = path.join(__dirname, 'public/install.ps1');

if (fs.existsSync(installShSrc)) {
  fs.copyFileSync(installShSrc, installShDest);
  console.log("Synced install.sh to website public folder.");
}
if (fs.existsSync(installPs1Src)) {
  fs.copyFileSync(installPs1Src, installPs1Dest);
  console.log("Synced install.ps1 to website public folder.");
}

console.log("Done.");
