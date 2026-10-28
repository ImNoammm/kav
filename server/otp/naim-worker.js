// Tel Aviv's weekend lines timetable (Na'im BeSofash) for Kav's server: the city's site answers addresses abroad with a
// maintenance page, so this Worker, placed near Tel Aviv, fetches it. Only that one file, only for Kav's key.
//
// A Cloudflare Worker (kav-naim-fetch) with placement region "aws:il-central-1" and a secret KEY, the same key that
// prepare.py sends from the server's otp/naim-worker file.
const SOURCE = "https://opendatasource.tel-aviv.gov.il/OpenData_Ducaments/gtfs.zip";

export default {
  async fetch(request, env) {
    if (request.headers.get("x-kav-key") !== env.KEY) return new Response("forbidden", { status: 403 });
    if (new URL(request.url).pathname === "/trace") {
      const trace = await fetch("https://cloudflare.com/cdn-cgi/trace");
      return new Response(await trace.text(), { headers: { "content-type": "text/plain" } });
    }
    const got = await fetch(SOURCE, { headers: { "user-agent": "kav-server" } });
    return new Response(got.body, {
      status: got.status,
      headers: { "content-type": got.headers.get("content-type") || "application/octet-stream" },
    });
  },
};
