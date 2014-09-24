import Request
import Response


Request req = request
Response rsp = response
ConfigObject con = config

ttl = "Debug"

rsp.sendHtml {
    html {
        head {
            title ttl
        }
        body {
            h1 ttl
            h2 'Request'
            p req
            h2 'Config'
            p con
        }
    }
}