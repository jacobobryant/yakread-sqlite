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
