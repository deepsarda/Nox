const fs = require('fs');
const path = require('path');

async function run() {
  const token = process.env.GITHUB_TOKEN;

  // Shouldn't ever happen. But just in case.
  if (!token) {
    console.error("GITHUB_TOKEN is required");
    process.exit(1);
  }

  // Shouldn't ever happen. But just in case.
  const repoEnv = process.env.GITHUB_REPOSITORY;
  if (!repoEnv) {
    console.error("GITHUB_REPOSITORY is required");
    process.exit(1);
  }
  const [owner, name] = repoEnv.split('/');

  const blogDir = path.join(process.cwd(), 'website/src/content/blog');
  if (!fs.existsSync(blogDir)) {
    console.error(`Blog directory not found at: ${blogDir}`);
    process.exit(1);
  }

  const files = fs.readdirSync(blogDir).filter(f => f.endsWith('.md'));
  if (files.length === 0) {
    console.log("No blog posts found.");
    return;
  }

  // Fetch Repo ID and Discussion Category IDs
  const query = `
    query($owner: String!, $name: String!) {
      repository(owner: $owner, name: $name) {
        id
        discussionCategories(first: 50) {
          nodes {
            id
            name
          }
        }
      }
    }
  `;

  console.log(`Fetching repository and discussion categories for ${owner}/${name}...`);
  const initRes = await fetch('https://api.github.com/graphql', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json',
      'User-Agent': 'nox-discussion-sync'
    },
    body: JSON.stringify({ query, variables: { owner, name } })
  });

  const initJson = await initRes.json();
  if (initJson.errors) {
    console.error("GraphQL Errors:", JSON.stringify(initJson.errors, null, 2));
    process.exit(1);
  }

  const repo = initJson.data.repository;
  if (!repo) {
    console.error(`Repository ${owner}/${name} not found or access denied.`);
    process.exit(1);
  }

  const repoId = repo.id;
  const categoryName = 'Announcements';
  const category = repo.discussionCategories.nodes.find(c => c.name === categoryName);

  if (!category) {
    console.error(`Discussion category "${categoryName}" not found in the repository. Please create it or adjust the script.`);
    console.log("Available categories:", repo.discussionCategories.nodes.map(n => n.name).join(', '));
    process.exit(1);
  }

  const categoryId = category.id;
  console.log(`Found Repository ID: ${repoId}`);
  console.log(`Found Category "${categoryName}" ID: ${categoryId}`);

  let updatedCount = 0;

  for (const file of files) {
    const filePath = path.join(blogDir, file);
    const postId = file.replace(/\.md$/, '');
    const fileContent = fs.readFileSync(filePath, 'utf8');

    // Parse frontmatter
    const fmMatch = fileContent.match(/^---\r?\n([\s\S]+?)\r?\n---/);
    if (!fmMatch) {
      console.log(`Skipping ${file}: No frontmatter found.`);
      continue;
    }

    const fmText = fmMatch[1];
    
    // Check if discussionNumber is already assigned
    const hasDiscMatch = fmText.match(/^discussionNumber:\s*(\d+)/m);
    if (hasDiscMatch) {
      console.log(`Skipping ${file}: Already has discussion number ${hasDiscMatch[1]}`);
      continue;
    }

    // Extract title
    const titleMatch = fmText.match(/^title:\s*"([^"]+)"|^title:\s*'([^']+)'|^title:\s*(.+)/m);
    const title = titleMatch ? (titleMatch[1] || titleMatch[2] || titleMatch[3]).trim() : postId;

    // Extract description
    const descMatch = fmText.match(/^description:\s*"([^"]+)"|^description:\s*'([^']+)'|^description:\s*(.+)/m);
    const description = descMatch ? (descMatch[1] || descMatch[2] || descMatch[3]).trim() : 'A new blog post from the Nox Team.';

    console.log(`Creating discussion for post "${title}" (ID: ${postId})...`);

    const postUrl = `https://deepsarda.github.io/Nox/blog/${postId}`;
    const body = `### New Blog Post Published!\n\n**${title}**\n\n${description}\n\n📖 [Read the full post on the Nox Website](${postUrl})\n\n---\n\nUse this thread to discuss the post, ask questions, or share your feedback!`;

    const mutation = `
      mutation($repoId: ID!, $catId: ID!, $title: String!, $body: String!) {
        createDiscussion(input: {
          repositoryId: $repoId,
          categoryId: $catId,
          title: $title,
          body: $body
        }) {
          discussion {
            number
          }
        }
      }
    `;

    const mutateRes = await fetch('https://api.github.com/graphql', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json',
        'User-Agent': 'nox-discussion-sync'
      },
      body: JSON.stringify({
        query: mutation,
        variables: {
          repoId,
          categoryId,
          title: `Blog: ${title}`,
          body
        }
      })
    });

    const mutateJson = await mutateRes.json();
    if (mutateJson.errors) {
      console.error(`Failed to create discussion for ${file}:`, JSON.stringify(mutateJson.errors, null, 2));
      continue;
    }

    const discNum = mutateJson.data.createDiscussion.discussion.number;
    console.log(`Discussion created successfully! Number: ${discNum}`);

    // Insert discussionNumber into frontmatter
    const newFmText = fmText.trim() + `\ndiscussionNumber: ${discNum}\n`;
    const newContent = fileContent.replace(/^---\r?\n([\s\S]+?)\r?\n---/, `---\n${newFmText}---`);

    fs.writeFileSync(filePath, newContent, 'utf8');
    console.log(`Updated frontmatter in ${file} with discussionNumber: ${discNum}`);
    updatedCount++;
  }

  console.log(`\nDone. Updated ${updatedCount} file(s).`);
}

run().catch(err => {
  console.error(err);
  process.exit(1);
});
