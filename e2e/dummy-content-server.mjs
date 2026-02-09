/**
 * Dummy content server for e2e tests.
 * Serves RSS feeds, blog posts, and other content that Yakread can ingest.
 * Runs on port 8888.
 */
import http from 'node:http';

const PORT = 8888;

const blogPost1 = `<!DOCTYPE html>
<html>
<head>
  <title>Test Blog Post 1 - The Future of Reading</title>
  <meta property="og:title" content="The Future of Reading" />
  <meta property="og:description" content="An exploration of how technology is changing the way we read." />
  <link rel="alternate" type="application/rss+xml" title="Test Blog RSS" href="http://localhost:${PORT}/feed.xml" />
</head>
<body>
  <article>
    <h1>The Future of Reading</h1>
    <p>By Jane Smith</p>
    <p>Published: 2025-01-15</p>
    <p>Technology has fundamentally changed how we consume written content. From e-readers to read-it-later apps, the landscape of reading continues to evolve at a rapid pace.</p>
    <p>In this article, we explore the key trends shaping the future of reading and what they mean for both readers and writers.</p>
    <p>The rise of AI-curated content feeds represents a significant shift in how readers discover new material. Rather than relying solely on social media algorithms or word of mouth, sophisticated recommendation engines can now surface relevant long-form content tailored to individual interests.</p>
  </article>
</body>
</html>`;

const blogPost2 = `<!DOCTYPE html>
<html>
<head>
  <title>Test Blog Post 2 - Building Better RSS Readers</title>
  <meta property="og:title" content="Building Better RSS Readers" />
  <meta property="og:description" content="Why RSS still matters and how to build modern RSS reading experiences." />
</head>
<body>
  <article>
    <h1>Building Better RSS Readers</h1>
    <p>By John Doe</p>
    <p>Published: 2025-01-20</p>
    <p>RSS (Really Simple Syndication) has been around since the late 1990s, and despite predictions of its demise, it remains one of the most reliable ways to follow content from websites you care about.</p>
    <p>Modern RSS readers need to go beyond simply displaying a list of unread items. They should provide smart filtering, categorization, and integration with other reading tools.</p>
  </article>
</body>
</html>`;

const blogPost3 = `<!DOCTYPE html>
<html>
<head>
  <title>Test Blog Post 3 - Newsletter Curation Tips</title>
  <meta property="og:title" content="Newsletter Curation Tips" />
  <meta property="og:description" content="How to manage newsletter subscriptions effectively." />
</head>
<body>
  <article>
    <h1>Newsletter Curation Tips</h1>
    <p>By Alice Johnson</p>
    <p>Published: 2025-02-01</p>
    <p>Email newsletters have experienced a renaissance in recent years. With platforms like Substack and Ghost making it easier than ever to publish, the volume of newsletter content has exploded.</p>
    <p>Here are some tips for managing your newsletter subscriptions without feeling overwhelmed.</p>
  </article>
</body>
</html>`;

// Long blog post that exceeds the 1000-character S3 storage threshold
const blogPostLong = `<!DOCTYPE html>
<html>
<head>
  <title>The Complete Guide to Digital Reading in 2025</title>
  <meta property="og:title" content="The Complete Guide to Digital Reading in 2025" />
  <meta property="og:description" content="A comprehensive guide covering every aspect of digital reading, from e-readers to read-it-later apps." />
</head>
<body>
  <article>
    <h1>The Complete Guide to Digital Reading in 2025</h1>
    <p>By Sarah Mitchell</p>
    <p>Published: 2025-03-01</p>
    <p>The landscape of digital reading has undergone a dramatic transformation over the past decade. What once started as simple e-book readers has evolved into a rich ecosystem of tools, platforms, and services designed to enhance the way we consume written content. In this comprehensive guide, we will explore every aspect of digital reading in 2025, from the hardware and software that powers it to the strategies and workflows that make it effective.</p>
    <h2>The Evolution of E-Readers</h2>
    <p>E-readers have come a long way since the first Kindle launched in 2007. Modern devices feature high-resolution E Ink displays with adjustable warm lighting, waterproof designs, and weeks-long battery life. The latest generation includes note-taking capabilities with stylus support, making them suitable for both reading and annotating. Companies like Kobo, reMarkable, and Boox have pushed the boundaries of what e-paper devices can do, offering open ecosystems that support multiple file formats and even Android apps.</p>
    <h2>Read-It-Later Applications</h2>
    <p>Services like Pocket, Instapaper, and Omnivore have made it possible to save articles for later reading. These tools strip away ads and distracting elements, presenting content in a clean, reader-friendly format. Many of them offer offline access, text-to-speech, and highlighting features. The rise of read-it-later apps reflects a growing desire among readers to be more intentional about their content consumption, moving away from the endless scroll of social media feeds toward curated collections of long-form content.</p>
    <h2>RSS: The Enduring Standard</h2>
    <p>Despite repeated predictions of its demise, RSS (Really Simple Syndication) continues to be one of the most reliable ways to follow content from websites you care about. RSS gives readers control over their information diet, free from algorithmic curation. Modern RSS readers like Feedly, Inoreader, and Miniflux provide sophisticated filtering, categorization, and integration features that make managing hundreds of feeds practical. The protocol's simplicity and openness ensure its continued relevance in an era of walled gardens and platform lock-in.</p>
    <h2>Newsletter Management</h2>
    <p>The newsletter boom of the 2020s brought millions of new email publications to reader inboxes. Managing this flood of content requires deliberate strategies: dedicated email addresses, aggressive filtering, and tools that aggregate newsletters into readable digests. Services like Kill the Newsletter convert email subscriptions into RSS feeds, bridging the gap between two popular content delivery mechanisms. The key is treating newsletters as a curated collection rather than an obligation to read everything that arrives.</p>
    <h2>Building Your Reading Workflow</h2>
    <p>The most effective digital readers develop personal workflows that match their habits and goals. This might involve using an RSS reader for discovery, a read-it-later app for processing, and an e-reader for focused reading sessions. The best workflows minimize friction between discovering content and actually reading it, while maintaining a system for capturing highlights and notes for future reference. Whatever tools you choose, the goal should be the same: spending less time managing your reading and more time actually reading.</p>
  </article>
</body>
</html>`;

const rssFeed = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
  <channel>
    <title>Test Blog</title>
    <link>http://localhost:${PORT}</link>
    <description>A test blog for Yakread e2e tests</description>
    <atom:link href="http://localhost:${PORT}/feed.xml" rel="self" type="application/rss+xml"/>
    <item>
      <title>The Future of Reading</title>
      <link>http://localhost:${PORT}/post/1</link>
      <guid>http://localhost:${PORT}/post/1</guid>
      <pubDate>Wed, 15 Jan 2025 12:00:00 GMT</pubDate>
      <description>An exploration of how technology is changing the way we read.</description>
    </item>
    <item>
      <title>Building Better RSS Readers</title>
      <link>http://localhost:${PORT}/post/2</link>
      <guid>http://localhost:${PORT}/post/2</guid>
      <pubDate>Mon, 20 Jan 2025 12:00:00 GMT</pubDate>
      <description>Why RSS still matters and how to build modern RSS reading experiences.</description>
    </item>
    <item>
      <title>Newsletter Curation Tips</title>
      <link>http://localhost:${PORT}/post/3</link>
      <guid>http://localhost:${PORT}/post/3</guid>
      <pubDate>Sat, 01 Feb 2025 12:00:00 GMT</pubDate>
      <description>How to manage newsletter subscriptions effectively.</description>
    </item>
  </channel>
</rss>`;

const atomFeed = `<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Test Atom Blog</title>
  <link href="http://localhost:${PORT}/atom.xml" rel="self"/>
  <link href="http://localhost:${PORT}"/>
  <id>http://localhost:${PORT}/atom.xml</id>
  <updated>2025-02-01T12:00:00Z</updated>
  <entry>
    <title>Atom Feed Post</title>
    <link href="http://localhost:${PORT}/post/atom-1"/>
    <id>http://localhost:${PORT}/post/atom-1</id>
    <updated>2025-02-01T12:00:00Z</updated>
    <summary>A test post from an Atom feed.</summary>
    <content type="html">&lt;p&gt;This is the content of an Atom feed post used for testing.&lt;/p&gt;</content>
  </entry>
</feed>`;

const homePage = `<!DOCTYPE html>
<html>
<head>
  <title>Test Blog Home</title>
  <link rel="alternate" type="application/rss+xml" title="Test Blog RSS" href="http://localhost:${PORT}/feed.xml" />
  <link rel="alternate" type="application/atom+xml" title="Test Blog Atom" href="http://localhost:${PORT}/atom.xml" />
</head>
<body>
  <h1>Test Blog</h1>
  <ul>
    <li><a href="/post/1">The Future of Reading</a></li>
    <li><a href="/post/2">Building Better RSS Readers</a></li>
    <li><a href="/post/3">Newsletter Curation Tips</a></li>
  </ul>
</body>
</html>`;

const routes = {
  '/': { body: homePage, contentType: 'text/html' },
  '/feed.xml': { body: rssFeed, contentType: 'application/rss+xml' },
  '/atom.xml': { body: atomFeed, contentType: 'application/atom+xml' },
  '/post/1': { body: blogPost1, contentType: 'text/html' },
  '/post/2': { body: blogPost2, contentType: 'text/html' },
  '/post/3': { body: blogPost3, contentType: 'text/html' },
  '/post/long': { body: blogPostLong, contentType: 'text/html' },
  '/post/atom-1': { body: '<html><body><h1>Atom Feed Post</h1><p>Content here.</p></body></html>', contentType: 'text/html' },
};

const server = http.createServer((req, res) => {
  const route = routes[req.url];
  if (route) {
    res.writeHead(200, { 'Content-Type': route.contentType });
    res.end(route.body);
  } else {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
  }
});

server.listen(PORT, () => {
  console.log(`Dummy content server running on http://localhost:${PORT}`);
});
