import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.eclipse.acceleo.Module;
import org.eclipse.acceleo.aql.AcceleoUtil;
import org.eclipse.acceleo.aql.evaluation.AcceleoEvaluator;
import org.eclipse.acceleo.aql.evaluation.strategy.DefaultGenerationStrategy;
import org.eclipse.acceleo.aql.evaluation.strategy.DefaultWriterFactory;
import org.eclipse.acceleo.aql.parser.AcceleoParser;
import org.eclipse.acceleo.aql.parser.ModuleLoader;
import org.eclipse.acceleo.query.runtime.impl.namespace.ClassLoaderQualifiedNameResolver;
import org.eclipse.acceleo.query.runtime.impl.namespace.JavaLoader;
import org.eclipse.emf.common.util.BasicMonitor;
import org.eclipse.emf.common.util.Diagnostic;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.URIConverter;
import org.eclipse.emf.ecore.resource.impl.ResourceSetImpl;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.XMLResource;
import org.eclipse.emf.ecore.xmi.impl.EcoreResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.m2m.qvt.oml.BasicModelExtent;
import org.eclipse.m2m.qvt.oml.ExecutionContextImpl;
import org.eclipse.m2m.qvt.oml.TransformationExecutor;

class GenerateOutputs {
    public static void main(String[] args) throws Exception {
        Path workspace = Path.of(args[0]).toAbsolutePath();
        Path outputDirectory = Files.createDirectories(Path.of(args[1]).toAbsolutePath());
        URI output = URI.createFileURI(outputDirectory.toString()).appendSegment("");
        var resources = new ResourceSetImpl();
        var factories = resources.getResourceFactoryRegistry().getExtensionToFactoryMap();
        factories.put("ecore", new EcoreResourceFactoryImpl());
        factories.put("xmi", new XMIResourceFactoryImpl());

        // Keep QVTo's platform:/resource references portable outside Eclipse.
        URIConverter.URI_MAP.put(URI.createURI("platform:/resource/"),
                URI.createFileURI(workspace.toString()).appendSegment(""));
        for (String metamodel : new String[] {
                "recipebook/model/recipebook.ecore",
                "MealSchedule/model/mealSchedule.ecore",
                "Supermarket/model/supermarket.ecore",
                "ShoppingList/model/shoppingList.ecore"
        }) {
            URI uri = URI.createFileURI(workspace.resolve(metamodel).toString());
            var type = (EPackage) resources.getResource(uri, true).getContents().getFirst();
            resources.getPackageRegistry().put(type.getNsURI(), type);
            resources.getPackageRegistry().put("platform:/resource/" + metamodel, type);
        }

        var schedule = resources.getResource(
                URI.createFileURI(Path.of(args[2]).toAbsolutePath().toString()), true);
        var supermarket = resources.getResource(
                URI.createFileURI(Path.of(args[3]).toAbsolutePath().toString()), true);
        EcoreUtil.resolveAll(resources);
        var inputs = new BasicModelExtent(schedule.getContents());
        var store = new BasicModelExtent(supermarket.getContents());
        var shopping = new BasicModelExtent();
        URI transformation = URI.createFileURI(workspace.resolve(
                "MealTransformations/transforms/ShoppingListPlan/NewTransformation.qvto").toString());
        var executor = new TransformationExecutor(transformation, resources.getPackageRegistry());
        try {
            check(executor.loadTransformation());
            check(executor.execute(new ExecutionContextImpl(), inputs, store, shopping));
        } finally {
            executor.cleanup();
        }
        var shoppingResource = resources.createResource(
                URI.createFileURI(outputDirectory.resolve("GeneratedShoppingList.xmi").toString()));
        shoppingResource.getContents().addAll(shopping.getContents());
        shoppingResource.save(Map.of(XMLResource.OPTION_ENCODING, "UTF-8",
                XMLResource.OPTION_SCHEMA_LOCATION, true));

        generate(resources, schedule, output, "MealScheduleToHTML");
        generate(resources, shoppingResource, output, "ShoppingListToHTML");
    }

    private static void generate(ResourceSetImpl resources, Resource model, URI output, String moduleName) {
        var resolver = new ClassLoaderQualifiedNameResolver(GenerateOutputs.class.getClassLoader(),
                resources.getPackageRegistry(), AcceleoParser.QUALIFIER_SEPARATOR);
        var environment = AcceleoUtil.newAcceleoQueryEnvironment(Map.of(), resolver, resources, false);
        for (String namespace : new String[] {"mealSchedule", "recipebook", "shoppingList"}) {
            environment.registerEPackage(resources.getPackageRegistry()
                    .getEPackage("http://www.example.org/" + namespace));
        }
        var evaluator = new AcceleoEvaluator(environment.getLookupEngine(), "\n");
        resolver.addLoader(new ModuleLoader(new AcceleoParser(), evaluator));
        resolver.addLoader(new JavaLoader(AcceleoParser.QUALIFIER_SEPARATOR, false));
        var module = (Module) resolver.resolve(moduleName);
        var strategy = new DefaultGenerationStrategy(resources.getURIConverter(), new DefaultWriterFactory());
        URI log = URI.createURI(moduleName + ".log").resolve(output);
        AcceleoUtil.generate(evaluator, environment, module, model, strategy, output, log, new BasicMonitor());
        check(evaluator.getGenerationResult().getDiagnostic());
    }

    private static void check(Diagnostic diagnostic) {
        if (diagnostic.getSeverity() != Diagnostic.OK) {
            throw new IllegalStateException(diagnostic.toString(), diagnostic.getException());
        }
    }
}
