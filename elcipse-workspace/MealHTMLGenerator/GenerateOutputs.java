import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
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

        URIConverter.URI_MAP.put(URI.createURI("platform:/resource/"),
                URI.createFileURI(workspace.toString()).appendSegment(""));

        for (String metamodel : new String[] {
                "recipebook/model/recipebook.ecore",
                "userPrefrences/model/userPrefrences.ecore",
                "CandidateRecipes/model/candidateRecipes.ecore",
                "MealSchedule/model/mealSchedule.ecore",
                "Supermarket/model/supermarket.ecore",
                "ShoppingList/model/shoppingList.ecore"
        }) {
            URI uri = URI.createFileURI(workspace.resolve(metamodel).toString());
            var type = (EPackage) resources.getResource(uri, true).getContents().getFirst();
            resources.getPackageRegistry().put(type.getNsURI(), type);
            resources.getPackageRegistry().put("platform:/resource/" + metamodel, type);
        }

        // Load input models
        var recipeBook = resources.getResource(
                URI.createFileURI(workspace.resolve("recipebook/model/RecipeBook.xmi").toString()), true);
        var userPrefs = resources.getResource(
                URI.createFileURI(workspace.resolve("userPrefrences/model/UserPrefrences.xmi").toString()), true);
        Path supermarketPath = (args.length > 3)
                ? Path.of(args[3]).toAbsolutePath()
                : workspace.resolve("Supermarket/model/Supermarket.xmi");
        var supermarket = resources.getResource(URI.createFileURI(supermarketPath.toString()), true);
        EcoreUtil.resolveAll(resources);

        // M2M 1: RecipeBook + UserPrefrences -> CandidateRecipes
        var candidatesOut = new BasicModelExtent();
        runQvt(resources,
                workspace.resolve("MealTransformations/transforms/CandidateRecipes/FilterCandidateRecipes.qvto"),
                new ExecutionContextImpl(),
                new BasicModelExtent(recipeBook.getContents()),
                new BasicModelExtent(userPrefs.getContents()),
                candidatesOut);
        var candidatesResource = saveModel(resources,
                outputDirectory.resolve("CandidateRecipes.xmi"), candidatesOut);

        // M2M 2: CandidateRecipes + UserPrefrences -> MealSchedule (weighted random)
        var scheduleOut = new BasicModelExtent();
        var scheduleCtx = new ExecutionContextImpl();
        int seed = new Random().nextInt(65536);
        scheduleCtx.setConfigProperty("randomSeed", seed);
        scheduleCtx.setConfigProperty("weekNumber", 5);
        runQvt(resources,
                workspace.resolve("MealTransformations/transforms/MealSchedulePlan/GenerateMealSchedule.qvto"),
                scheduleCtx,
                new BasicModelExtent(candidatesResource.getContents()),
                new BasicModelExtent(userPrefs.getContents()),
                scheduleOut);
        var scheduleResource = saveModel(resources,
                outputDirectory.resolve("GeneratedMealSchedule.xmi"), scheduleOut);

        // M2M 3: MealSchedule + Supermarket -> ShoppingList
        var shoppingOut = new BasicModelExtent();
        runQvt(resources,
                workspace.resolve("MealTransformations/transforms/ShoppingListPlan/MealScheduleToShoppingList.qvto"),
                new ExecutionContextImpl(),
                new BasicModelExtent(scheduleResource.getContents()),
                new BasicModelExtent(supermarket.getContents()),
                shoppingOut);
        var shoppingResource = saveModel(resources,
                outputDirectory.resolve("GeneratedShoppingList.xmi"), shoppingOut);

        // M2T: Generate HTML outputs
        generate(resources, scheduleResource, output, "MealScheduleToHTML");
        generate(resources, shoppingResource, output, "ShoppingListToHTML");
        System.out.println("Pipeline complete (randomSeed=" + seed + ")");
    }

    private static void runQvt(ResourceSetImpl resources, Path qvtoPath,
            ExecutionContextImpl context, BasicModelExtent... extents) {
        URI transformation = URI.createFileURI(qvtoPath.toString());
        var executor = new TransformationExecutor(transformation, resources.getPackageRegistry());
        boolean loaded = false;
        try {
            check(executor.loadTransformation());
            loaded = true;
            check(executor.execute(context, extents));
        } finally {
            if (loaded) {
                executor.cleanup();
            }
        }
    }

    private static Resource saveModel(ResourceSetImpl resources, Path path,
            BasicModelExtent extent) throws Exception {
        var resource = resources.createResource(URI.createFileURI(path.toString()));
        resource.getContents().addAll(extent.getContents());
        resource.save(Map.of(
                XMLResource.OPTION_ENCODING, "UTF-8",
                XMLResource.OPTION_SCHEMA_LOCATION, true));
        return resource;
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
