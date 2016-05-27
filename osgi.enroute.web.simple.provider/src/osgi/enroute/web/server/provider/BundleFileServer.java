package osgi.enroute.web.server.provider;

import java.io.*;
import java.util.*;

import javax.servlet.*;
import javax.servlet.http.*;

import org.osgi.framework.*;
import org.osgi.service.component.annotations.*;
import org.osgi.service.http.whiteboard.*;
import org.osgi.service.log.*;
import org.osgi.util.tracker.*;

import osgi.enroute.web.server.cache.*;

@Component(
		name = "osgi.enroute.web.service.provider.bfs",
		property = {
				HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/bnd", 
				"name=DispatchServlet", 
				Constants.SERVICE_RANKING + ":Integer=100"
		}, 
		service = Servlet.class, 
		configurationPolicy = ConfigurationPolicy.OPTIONAL, 
		immediate = true)
public class BundleFileServer extends HttpServlet {

	private static final long					serialVersionUID	= 1L;

	protected WebServerConfig					config;
	private BundleTracker<?>					tracker;
	protected Map<String, Bundle>				bundles = new HashMap<>();
	private ResponseWriter						writer;

	private LogService							log;
	private Cache								cache;

	@Activate
	void activate(WebServerConfig config, BundleContext context) throws Exception {
		this.config = config;
		writer = new ResponseWriter(config);

		tracker = new BundleTracker<Bundle>(context, Bundle.ACTIVE | Bundle.STARTING, null) {
			public Bundle addingBundle(Bundle bundle, BundleEvent event) {
				String bsn = bundle.getSymbolicName();
				boolean found = false;
				if (config.debug())
					found = bundle.findEntries("static/debug/" + bsn, "*", false) != null;
				else
					found = bundle.findEntries("static/" + bsn, "*", false) != null;

				if(!found)
					return null;

				bundles.put(bsn, bundle);
				return bundle;
			}

			@Override
			public void removedBundle(Bundle bundle, BundleEvent event, Bundle object) {
				bundles.remove(bundle.getSymbolicName());
			}
		};

		tracker.open();
	}

	public void doGet(HttpServletRequest rq, HttpServletResponse rsp) throws ServletException, IOException {

		String path = rq.getPathInfo();

		if (path == null ) {
			rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		String bsn = null;

		if (path.startsWith("/"))
			path = path.substring(1);

		if (path.contains("/"))
			bsn = path.substring(0, path.indexOf('/'));
		else
			bsn = path;

		Bundle b = bundles.get(bsn);
		if (b == null) {
			rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
			return;
		}

		try {
			FileCache c = cache.getFromBundle(b, path);
			if (c == null ) {
				rsp.sendError(HttpServletResponse.SC_NOT_FOUND);
				return;
			}

			writer.writeResponse(rq, rsp, c);
		}
		catch (RedirectException e) {
			rsp.sendRedirect(e.getPath());
		}
		catch (Exception e) {
			log.log(LogService.LOG_ERROR, "Internal webserver error", e);
			if (config.exceptions())
				throw new RuntimeException(e);

			try {
				PrintWriter pw = rsp.getWriter();
				pw.println("Internal server error\n");
				rsp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			}
			catch (Exception ee) {
				log.log(LogService.LOG_ERROR, "Second level internal webserver error", ee);
			}
		}
	}

	@Deactivate
	void deactivate() {
		tracker.close();
	}

	@Reference
	void setLog(LogService log) {
		this.log = log;
	}

	@Reference
	void setCache(Cache cache) {
		this.cache = cache;
	}
}