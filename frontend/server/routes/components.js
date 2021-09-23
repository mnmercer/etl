"use strict";

const request = require("request");
const config = require("../configuration");
const errors = require("../error-codes");

const router = require("express").Router();
module.exports = router;

const storageApiUrlPrefix = config.storage.url + "/api/v1/components";

router.get("", (req, res) => {
  const options = {
    "url": storageApiUrlPrefix + "/list",
    "headers": updateHeaders(req.headers),
  };
  request.get(options)
    .on("error", (error) => handleConnectionError(res, error))
    .pipe(res);
});

function updateHeaders(headers) {
  // We use json-ld as default to make it easy to see pipelines from
  // browsers.
  if (headers.accept === "*/*") {
    return {
      ...headers,
      "accept": "application/ld+json"
    }
  }
  return headers;
}

function handleConnectionError(res, error) {
  console.error("Request failed:\n", error);
  res.status(503).json({
    "error": {
      "type": errors.CONNECTION,
      "source": "FRONTEND"
    }
  });
}

router.get("/definition", (req, res) => {
  const options = {
    "url": config.storage.url +
    "/api/v1/components/definition?iri=" + req.query.iri,
    "headers": updateHeaders(req.headers),
  };
  request.get(options)
    .on("error", (error) => handleConnectionError(res, error))
    .pipe(res);
});

router.post("", (req, res) => {
  const url = storageApiUrlPrefix + "/";
  req.pipe(request.post(url, {"form": req.body}), {"end": false})
    .on("error", (error) => handleConnectionError(res, error))
    .pipe(res);
});

router.delete("/:id", (req, res) => {
  const url = storageApiUrlPrefix + "?iri="
    + encodeURI(config.storage.domain + req.originalUrl);
  request.del(url)
    .on("error", (error) => handleConnectionError(res, error))
    .pipe(res);
});
