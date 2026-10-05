import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// Structural checks use the YAML parser already bundled by Spring Boot.
// No rendered objects or secret contents are printed.
class RageQuitCheck {
    static void require(boolean value) {
        if (!value) {
            var frame = StackWalker.getInstance().walk(frames -> frames.skip(1).findFirst().orElse(null));
            String location = frame == null ? "unknown" : frame.getMethodName() + ":" + frame.getLineNumber();
            throw new IllegalArgumentException("structural assertion failed at " + location);
        }
    }
    static Object at(Object value, String... keys) {
        for (String key : keys) { if (!(value instanceof Map<?, ?> map)) return null; value = map.get(key); }
        return value;
    }
    static List<?> list(Object value) { return value instanceof List<?> l ? l : List.of(); }
    static Set<String> keys(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Set.of();
        var result = new HashSet<String>();
        for (Object key : map.keySet()) result.add(String.valueOf(key));
        return Set.copyOf(result);
    }
    static boolean isSopsEncrypted(Object value) {
        return value instanceof String text && text.startsWith("ENC[") && text.endsWith("]");
    }
    static List<Object> load(Path path) throws Exception {
        var result = new ArrayList<Object>();
        try (var reader = Files.newBufferedReader(path)) {
            for (Object doc : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(reader)) if (doc != null) result.add(doc);
        }
        return result;
    }
    static Object named(List<Object> docs, String kind, String name) {
        var found = docs.stream().filter(d -> kind.equals(at(d,"kind")) && name.equals(at(d,"metadata","name"))).toList();
        require(found.size() == 1); return found.getFirst();
    }
    static void app(List<Object> docs) {
        require(docs.stream().noneMatch(d -> Set.of("Namespace","ServiceAccount","Role","RoleBinding","ClusterRole","ClusterRoleBinding").contains(at(d,"kind"))));
        for (var d : docs) require("web".equals(at(d,"metadata","namespace")));
        var deployment = named(docs,"Deployment","rage-quit");
        require(Integer.valueOf(1).equals(at(deployment,"spec","replicas")));
        require("Recreate".equals(at(deployment,"spec","strategy","type")));
        var pod = at(deployment,"spec","template","spec");
        require(Boolean.FALSE.equals(at(pod,"automountServiceAccountToken")));
        require(Boolean.TRUE.equals(at(pod,"securityContext","runAsNonRoot")));
        for (String field : List.of("runAsUser","runAsGroup","fsGroup")) require(Integer.valueOf(10001).equals(at(pod,"securityContext",field)));
        require("RuntimeDefault".equals(at(pod,"securityContext","seccompProfile","type")));
        var containers = list(at(pod,"containers")); require(containers.size() == 1); var container = containers.getFirst();
        require(Boolean.TRUE.equals(at(container,"securityContext","readOnlyRootFilesystem")));
        require(Boolean.FALSE.equals(at(container,"securityContext","allowPrivilegeEscalation")));
        require(list(at(container,"securityContext","capabilities","drop")).equals(List.of("ALL")));
        String image = String.valueOf(at(container,"image"));
        require(image.matches("ghcr\\.io/dmgiangi/calcifer-rage-quit(@sha256:[0-9a-f]{64}|:operator-must-release)"));
        require(at(container,"resources","limits","memory") != null && at(container,"resources","limits","cpu") != null);
        for (String probe : List.of("startupProbe","livenessProbe","readinessProbe")) {
            String path = probe.equals("readinessProbe") ? "readiness" : "liveness";
            require(("/actuator/health/" + path).equals(at(container,probe,"httpGet","path")));
        }
        var env = list(at(container,"env")); require(env.size() == 1);
        require("RAGE_QUIT_CLIENT_SECRET".equals(at(env.getFirst(),"name")));
        require("rage-quit-secrets".equals(at(env.getFirst(),"valueFrom","secretKeyRef","name")));
        require("RAGE_QUIT_CLIENT_SECRET".equals(at(env.getFirst(),"valueFrom","secretKeyRef","key")));
        require(Boolean.FALSE.equals(at(env.getFirst(),"valueFrom","secretKeyRef","optional")));
        var volumes = list(at(pod,"volumes")); require(volumes.size() == 2);
        require(volumes.stream().anyMatch(v -> "64Mi".equals(at(v,"emptyDir","sizeLimit"))));
        require(volumes.stream().anyMatch(v -> "rage-quit-data".equals(at(v,"persistentVolumeClaim","claimName"))));
        var pvc = named(docs,"PersistentVolumeClaim","rage-quit-data");
        require("disabled".equals(at(pvc,"metadata","annotations","kustomize.toolkit.fluxcd.io/prune")));
        require("local-path".equals(at(pvc,"spec","storageClassName")));
        require("1Gi".equals(at(pvc,"spec","resources","requests","storage")));
        require(list(at(pvc,"spec","accessModes")).equals(List.of("ReadWriteOnce")));
        var config = named(docs,"ConfigMap","rage-quit-config");
        require("https://auth.calcifer.tech".equals(at(config,"data","RAGE_QUIT_ISSUER")));
        require("rage-quit".equals(at(config,"data","RAGE_QUIT_CLIENT_ID")));
        require("/data/rage-quit.sqlite".equals(at(config,"data","RAGE_QUIT_DATABASE")));
        require(at(config,"data","RAGE_QUIT_CLIENT_SECRET") == null);
        var route = named(docs,"IngressRoute","rage-quit");
        require(list(at(route,"spec","entryPoints")).equals(List.of("websecure")));
        require("rage-quit-tls".equals(at(route,"spec","tls","secretName")));
        require("Host(`rage-quit.calcifer.tech`) && !PathPrefix(`/actuator`)".equals(at(list(at(route,"spec","routes")).getFirst(),"match")));
        var certificate = named(docs,"Certificate","rage-quit");
        require("rage-quit-tls".equals(at(certificate,"spec","secretName")));
        require("letsencrypt-production-azure".equals(at(certificate,"spec","issuerRef","name")));
        var policy = named(docs,"NetworkPolicy","rage-quit");
        require(list(at(policy,"spec","policyTypes")).equals(List.of("Ingress","Egress")));
        require(!list(at(policy,"spec","ingress")).isEmpty() && !list(at(policy,"spec","egress")).isEmpty());
        named(docs,"Middleware","rage-quit-security-headers");
    }
    static void secretReferences(List<Object> appDocs, Path appOverlayPath, Path secretTemplatePath,
                                List<Object> cloudAuthDocs, List<Object> homeAuthDocs, Path authProfilePath) throws Exception {
        Object appOverlay = load(appOverlayPath).getFirst();
        require("Kustomization".equals(at(appOverlay,"kind")));
        var appResources = list(at(appOverlay,"resources")).stream().map(String::valueOf).toList();
        require(appResources.contains("secrets.sops.yaml"));
        require(appResources.stream().noneMatch(resource -> resource.endsWith("secret-template.yaml")));
        Object fixture = named(load(secretTemplatePath),"Secret","rage-quit-secrets");
        require("web".equals(at(fixture,"metadata","namespace")));
        require("Opaque".equals(at(fixture,"type")));
        require(at(fixture,"data") == null);
        require(keys(at(fixture,"stringData")).equals(Set.of("RAGE_QUIT_CLIENT_SECRET")));
        require("REPLACE_WITH_SECRET".equals(at(fixture,"stringData","RAGE_QUIT_CLIENT_SECRET")));
        Object appSecret = named(appDocs,"Secret","rage-quit-secrets");
        require("web".equals(at(appSecret,"metadata","namespace")));
        Object appSecretType = at(appSecret,"type");
        require(appSecretType == null || "Opaque".equals(appSecretType));
        require(keys(at(appSecret,"data")).equals(Set.of("RAGE_QUIT_CLIENT_SECRET")));
        require(at(appSecret,"stringData") == null);
        require(isSopsEncrypted(at(appSecret,"data","RAGE_QUIT_CLIENT_SECRET")));

        Object authProfile = load(authProfilePath).getFirst();
        require("${AUTH_CLIENT_RAGE_QUIT_SECRET:}".equals(at(authProfile,"identity","clients","rage-quit","secret")));
        Object cloudDeployment = named(cloudAuthDocs,"Deployment","authorization-server");
        Object homeDeployment = named(homeAuthDocs,"Deployment","authorization-server");
        for (Object deployment : List.of(cloudDeployment,homeDeployment)) {
            require(list(at(deployment,"spec","template","spec","containers")).stream().anyMatch(container ->
                list(at(container,"envFrom")).stream().anyMatch(source ->
                    "authorization-server-secrets".equals(at(source,"secretRef","name")))));
        }
        Object cloudSecret = named(cloudAuthDocs,"Secret","authorization-server-secrets");
        Object homeSecret = named(homeAuthDocs,"Secret","authorization-server-secrets");
        require("authorization".equals(at(cloudSecret,"metadata","namespace")));
        require("authorization".equals(at(homeSecret,"metadata","namespace")));
        boolean cloudHasRageQuitKey = keys(at(cloudSecret,"data")).contains("AUTH_CLIENT_RAGE_QUIT_SECRET");
        boolean homeHasRageQuitKey = keys(at(homeSecret,"data")).contains("AUTH_CLIENT_RAGE_QUIT_SECRET");
        require(cloudHasRageQuitKey == homeHasRageQuitKey);
        require(isSopsEncrypted(at(cloudSecret,"data","AUTH_CLIENT_RAGE_QUIT_SECRET")));
        require(isSopsEncrypted(at(homeSecret,"data","AUTH_CLIENT_RAGE_QUIT_SECRET")));
        System.out.println("Rage Quit auth Secret key metadata is present and encrypted on both edges.");
        require(appDocs.stream().anyMatch(d -> "Deployment".equals(at(d,"kind"))
            && "rage-quit".equals(at(d,"metadata","name"))));
    }
    static void graph(List<Object> docs, boolean cloud) {
        var edges = new HashMap<String,List<String>>();
        for (var d : docs) if ("Kustomization".equals(at(d,"kind")) && String.valueOf(at(d,"apiVersion")).startsWith("kustomize.toolkit.fluxcd.io/")) {
            String name = String.valueOf(at(d,"metadata","name"));
            edges.put(name,list(at(d,"spec","dependsOn")).stream().map(v -> String.valueOf(at(v,"name"))).toList());
            if (!cloud) require(!String.valueOf(at(d,"spec","path")).contains("rage-quit"));
        }
        if (cloud) {
            require(new HashSet<>(edges.getOrDefault("rage-quit",List.of())).equals(Set.of("authorization-server","cert-manager-config","cloud-apps")));
            require(edges.get("cloud-apps").equals(List.of("cert-manager-config")));
            require(edges.get("authorization-server").equals(List.of("cert-manager-config","authorization-state","private-transit")));
            require("./clusters/apps/rage-quit/overlays/cloud".equals(at(named(docs,"Kustomization","rage-quit"),"spec","path")));
        } else require(docs.stream().noneMatch(d -> String.valueOf(at(d,"metadata","name")).startsWith("rage-quit")));
        for (String name : edges.keySet()) visit(name,edges,new HashSet<>());
    }
    static void visit(String name, Map<String,List<String>> edges, Set<String> stack) {
        require(stack.add(name));
        for (String dependency : edges.getOrDefault(name,List.of())) { require(edges.containsKey(dependency)); visit(dependency,edges,stack); }
        stack.remove(name);
    }
    static void workflow(Object flow) {
        Object triggers = at(flow,"on");
        if (triggers == null && flow instanceof Map<?,?> m) triggers = m.get(Boolean.TRUE); // YAML 1.1 "on"
        require(triggers instanceof Map<?,?> m && m.size() == 1 && m.containsKey("workflow_dispatch"));
        require(Boolean.TRUE.equals(at(triggers,"workflow_dispatch","inputs","version","required")));
        require("false".equals(String.valueOf(at(flow,"concurrency","cancel-in-progress"))));
        var jobs = at(flow,"jobs"); require(jobs instanceof Map<?,?> m && m.size() == 2);
        require("read".equals(at(jobs,"verify","permissions","contents")));
        require("verify".equals(at(jobs,"publish","needs")));
        require("write".equals(at(jobs,"publish","permissions","packages")));
        require("write".equals(at(jobs,"publish","permissions","contents")));
        String verify = list(at(jobs,"verify","steps")).stream().map(v -> String.valueOf(at(v,"run"))).reduce("",(a,b)->a+b+"\n");
        for (String check : List.of("rage-quit-version.sh","mvn -B -ntp -f rage-quit/pom.xml verify","rage-quit-validate.sh","rage-quit-container-check.sh")) require(verify.contains(check));
        for (String job : List.of("verify","publish")) for (var step : list(at(jobs,job,"steps"))) {
            require(at(step,"continue-on-error") == null && at(step,"if") == null);
            require(!String.valueOf(at(step,"run")).contains("${{ inputs.version }}"));
        }
        String publish = list(at(jobs,"publish","steps")).stream().map(v -> String.valueOf(at(v,"run"))).reduce("",(a,b)->a+b+"\n");
        require(publish.contains("rage-quit-publish.sh"));
    }
    public static void main(String[] args) {
        try {
            require(args.length == 9);
            var appDocs = load(Path.of(args[0]));
            var cloudDocs = load(Path.of(args[1]));
            var homeDocs = load(Path.of(args[2]));
            app(appDocs); graph(cloudDocs,true); graph(homeDocs,false);
            secretReferences(appDocs,Path.of(args[5]),Path.of(args[4]),load(Path.of(args[6])),load(Path.of(args[7])),Path.of(args[8]));
            workflow(load(Path.of(args[3])).getFirst());
            System.out.println("Manifest hardening, secret-reference metadata, Cloud-only dependency graph and manual gated workflow checks passed; rendered Secret values were not output.");
        } catch (Throwable failure) {
            String message = failure.getMessage();
            if (failure instanceof IllegalArgumentException && message != null
                && message.startsWith("structural assertion failed at ")) {
                System.err.println("Delivery structural validation failed at "
                    + message.substring("structural assertion failed at ".length()) + " (rendered content suppressed).");
            } else System.err.println("Delivery structural validation failed (rendered content suppressed).");
            System.exit(1);
        }
    }
}
