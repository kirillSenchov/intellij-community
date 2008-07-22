package org.jetbrains.idea.svn;

import org.tmatesoft.svn.core.SVNErrorMessage;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.auth.*;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNAuthenticationManager;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.util.StringTokenizer;

/**
 * Created by IntelliJ IDEA.
 * User: alex
 * Date: Sep 24, 2005
 * Time: 5:25:35 PM
 * To change this template use File | Settings | File Templates.
 */
public class SvnAuthenticationManager extends DefaultSVNAuthenticationManager {
    public SvnAuthenticationManager(final File configDirectory) {
        super(configDirectory, true, null, null);
    }

    protected ISVNAuthenticationProvider createCacheAuthenticationProvider(File authDir) {
        return new ApplicationAuthenticationProvider();
    }

  // todo clarify whether it is OK that this method is not used, no more overrides "create Default..."
  protected ISVNAuthenticationProvider createDefaultAuthenticationProvider(String userName, String password, boolean allowSave) {
        return new ISVNAuthenticationProvider() {
            public SVNAuthentication requestClientAuthentication(String kind, SVNURL url, String realm, SVNErrorMessage errorMessage, SVNAuthentication previousAuth, boolean authMayBeStored) {
                return null;
            }
            public int acceptServerAuthentication(SVNURL url, String realm, Object certificate, boolean resultMayBeStored) {
                return ACCEPTED;
            }
        };
    }

  class ApplicationAuthenticationProvider implements ISVNAuthenticationProvider, IPersistentAuthenticationProvider {

        public SVNAuthentication requestClientAuthentication(String kind, SVNURL url, String realm, SVNErrorMessage errorMessage, SVNAuthentication previousAuth, boolean authMayBeStored) {
          if (ISVNAuthenticationManager.SSL.equals(kind)) {
                  String host = url.getHost();
                  Map properties = getHostProperties(host);
                  String sslClientCert = (String) properties.get("ssl-client-cert-file"); // PKCS#12
                  String sslClientCertPassword = (String) properties.get("ssl-client-cert-password");
                  File clientCertFile = sslClientCert != null ? new File(sslClientCert) : null;
                  return new SVNSSLAuthentication(clientCertFile, sslClientCertPassword, authMayBeStored);
          }

          // get from key-ring, use realm.
            Map info = SvnApplicationSettings.getInstance().getAuthenticationInfo(realm, kind);
            // convert info to SVNAuthentication.
            if (info != null && !info.isEmpty() && info.get("username") != null) {
                if (ISVNAuthenticationManager.PASSWORD.equals(kind)) {
                    return new SVNPasswordAuthentication((String) info.get("username"), (String) info.get("password"), authMayBeStored);
                } else if (ISVNAuthenticationManager.SSH.equals(kind)) {
                    int port = url.hasPort() ? url.getPort() : -1;
                    if (port < 0 && info.get("port") != null) {
                        port = Integer.parseInt((String) info.get("port"));
                    }
                    if (port < 0) {
                        port = 22;
                    }
                    if (info.get("key") != null) {
                        File keyPath = new File((String) info.get("key"));
                        return new SVNSSHAuthentication((String) info.get("username"), keyPath, (String) info.get("passphrase"), port, authMayBeStored);
                    } else if (info.get("password") != null) {
                        return new SVNSSHAuthentication((String) info.get("username"), (String) info.get("password"), port, authMayBeStored);
                    }
                } if (ISVNAuthenticationManager.USERNAME.equals(kind)) {
                    return new SVNUserNameAuthentication((String) info.get("username"), authMayBeStored);
                }
            }
            return null;
        }

        public int acceptServerAuthentication(SVNURL url, String realm, Object certificate, boolean resultMayBeStored) {
            return ACCEPTED_TEMPORARY;
        }

        public void saveAuthentication(SVNAuthentication auth, String kind, String realm) {
            if (auth.getUserName() == null || "".equals(auth.getUserName())) {
                return;
            }
            Map<String, String> info = new HashMap<String, String>();
            info.put("kind", kind);
            // convert info to SVNAuthentication.
            info.put("username", auth.getUserName());
            if (auth instanceof SVNPasswordAuthentication) {
                info.put("password", ((SVNPasswordAuthentication) auth).getPassword());
            } else if (auth instanceof SVNSSHAuthentication) {
                SVNSSHAuthentication sshAuth = (SVNSSHAuthentication) auth;
                if (sshAuth.getPrivateKeyFile() != null) {
                    info.put("key", sshAuth.getPrivateKeyFile().getAbsolutePath());
                    if (sshAuth.getPassphrase() != null) {
                        info.put("passphrase", sshAuth.getPassphrase());
                    }
                } else if (sshAuth.getPassword() != null) {
                    info.put("password", sshAuth.getPassword());
                }
                if (sshAuth.getPortNumber() >= 0) {
                    info.put("port", Integer.toString(sshAuth.getPortNumber()));
                }
            }
            SvnApplicationSettings.getInstance().saveAuthenticationInfo(realm, kind, info);
        }

    }

  // 30 seconds
  private final static int DEFAULT_READ_TIMEOUT = 30 * 1000;

  @Override
  public int getReadTimeout(final SVNRepository repository) {
    String protocol = repository.getLocation().getProtocol();
    if ("http".equals(protocol) || "https".equals(protocol)) {
        String host = repository.getLocation().getHost();
        Map properties = getHostProperties(host);
        String timeout = (String) properties.get("http-timeout");
        if (timeout != null) {
            try {
                return Integer.parseInt(timeout)*1000;
            } catch (NumberFormatException nfe) {
              // use default
            }
        }
        return DEFAULT_READ_TIMEOUT;
    }
    return 0;
  }

  // taken from default manager as is
  private Map getHostProperties(String host) {
      Map globalProps = getServersFile().getProperties("global");
      String groupName = getGroupName(getServersFile().getProperties("groups"), host);
      if (groupName != null) {
          Map hostProps = getServersFile().getProperties(groupName);
          globalProps.putAll(hostProps);
      }
      return globalProps;
  }
  // taken from default manager as is
  private static String getGroupName(Map groups, String host) {
      for (Iterator names = groups.keySet().iterator(); names.hasNext();) {
          String name = (String) names.next();
          String pattern = (String) groups.get(name);
          for(StringTokenizer tokens = new StringTokenizer(pattern, ","); tokens.hasMoreTokens();) {
              String token = tokens.nextToken();
              if (DefaultSVNOptions.matches(token, host)) {
                  return name;
              }
          }
      }
      return null;
  }
}
