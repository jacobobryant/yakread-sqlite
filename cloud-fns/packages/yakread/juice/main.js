let juice = require('juice');

function main(opts) {
  let webResources = {
    images: false,
    scripts: false,
    svgs: false,
    //links: false,
  };
  try {
    let html = juice(opts['html'], { webResources: webResources });
    return { body: { html } };
  } catch (e) {
    console.error('juice error:', e.message);
    return { body: { html: null, error: e.message } };
  }
}

exports.main = main;
