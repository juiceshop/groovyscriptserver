import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import groovy.text.TemplateEngine
import groovy.transform.CompileDynamic
import groovy.transform.Field
import info.bliki.wiki.model.WikiModel
import org.simpleframework.http.Status

import java.util.logging.Logger

@Lazy @Field GroovyShell shell = new GroovyShell()
@Lazy @Field TemplateEngine templateEngine = new SimpleTemplateEngine()


@Field static Logger log = Logger.getLogger(this.class.simpleName)

Request req = request
Response rsp = response
ConfigObject conf = config


def path = req.path
def name = path.name
def requestFile = req.requestFile
log.info "Request File: "+requestFile

// Check if requested file lies under document root
if (!requestFile.toString().startsWith(conf.documentRootFile.toString())) {
    rsp.error(Status.FORBIDDEN, "Access not allowed: $path")
    return
}

File scriptFile = null

// If requested file is a directory and there is no slash at the end of the path
// add slash to path and redirect.
if (name && requestFile.isDirectory()) {
    rsp.redirect(path.path + '/')
    return
}

if (requestFile.isDirectory()) {
    serveDirectory(requestFile, req, rsp)
    return
}

if (path.extension=='groovy') {
    log.info "Serve Script: "+requestFile
    evaluate(requestFile)
    return
}

if (path.extension=='gsp') {
    log.info "ServeTemplate: " + requestFile
    Template template = templateEngine.createTemplate(requestFile)
    Writable result = template.make(this.binding.variables)
    rsp.printStream.println(result)
    return
}

// Try to find a handler script for the extension
def handlerName = 'handleExtension.' + (path.extension?:'null') + '.groovy'
scriptFile = new File(handlerName)
if (scriptFile.exists()) {
    evaluate(scriptFile)
    return
}

// Check if requested file exists (creates error response if needed)
if (!rsp.checkFileExists(req)) return

// OK, nothing left, send file
rsp.contentType = MimeTypes[path.extension]
rsp << requestFile


void serveDirectory(File requestFile, Request req, Response rsp) {

    def ttl = "Directory: " + req.path.path
    def parentPath = req.path.path
    if (parentPath.endsWith('/')) parentPath = parentPath.substring(0,parentPath.length()-1)
    int p = parentPath.lastIndexOf('/')
    if (p>=0) parentPath = parentPath[0..p]
    List<File> fileList = requestFile.listFiles()
    rsp.sendHtml {
        html {
            head {
                title ttl
            }
            body {
                h1 ttl
                ul {
                    if (parentPath) {
                        li {
                            a (href:parentPath,'..')
                        }
                    }
                    for (file in fileList) {
                        String fileName = file.name
                        if (file.isDirectory()) {
                            fname += '/'
                            li {
                                a (href:fileName,fileName)
                            }
                        }
                    }
                    for (file in fileList) {
                        String fileName = file.name
                        if (!file.isDirectory()) {
                            li {
                                a (href:fileName,fileName)
                            }
                        }
                    }
                }
//                    if (!fileList) {
//                        p 'Empty directory'
//                    }
            }
        }
    }
}

class MimeTypes {
    static final TEXT_PLAIN = 'text/plain'
    static final TEXT_HTML = 'text/html'
    static final DEFAULT = 'application/octet-stream'
    private static Map types = [
            txt: TEXT_PLAIN,
            html: TEXT_HTML
    ]
    static String get(String extension) {
        return types[extension]?:DEFAULT
    }
    static String getAt(String extension) {
        return get(extension)
    }
}