package meghanada.completion;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static meghanada.reflect.asm.CachedASMReflector.cloneClassIndex;

import com.google.common.base.Joiner;
import com.google.common.collect.TreeBasedTable;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import meghanada.analyze.AccessSymbol;
import meghanada.analyze.ClassScope;
import meghanada.analyze.FieldAccess;
import meghanada.analyze.MethodCall;
import meghanada.analyze.Source;
import meghanada.analyze.TypeScope;
import meghanada.analyze.Variable;
import meghanada.cache.GlobalCache;
import meghanada.completion.matcher.CamelCaseMatcher;
import meghanada.completion.matcher.CompletionMatcher;
import meghanada.completion.matcher.ContainsMatcher;
import meghanada.completion.matcher.FuzzyMatcher;
import meghanada.completion.matcher.PrefixMatcher;
import meghanada.config.Config;
import meghanada.index.IndexDatabase;
import meghanada.project.Project;
import meghanada.reflect.CandidateUnit;
import meghanada.reflect.ClassIndex;
import meghanada.reflect.FieldDescriptor;
import meghanada.reflect.MemberDescriptor;
import meghanada.reflect.MethodDescriptor;
import meghanada.reflect.asm.CachedASMReflector;
import meghanada.utils.ClassNameUtils;
import meghanada.utils.FileUtils;
import meghanada.utils.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class JavaCompletion {

  private static final Logger log = LogManager.getLogger(JavaCompletion.class);
  private final TreeBasedTable<File, CandidateUnit, Integer> statisticsTable;

  private Project project;
  private Collection<? extends CandidateUnit> hits;

  public JavaCompletion(final Project project) {
    this.project = project;
    this.statisticsTable = createStatisticsTable();
  }

  @Nonnull
  private static Collection<? extends CandidateUnit> annotationCompletion(
      final Source source, final int line, final int column, final String prefix) {
    String classPrefix = prefix.substring(1);
    CompletionMatcher matcher = getClassCompletionMatcher(classPrefix);
    CachedASMReflector reflector = CachedASMReflector.getInstance();
    return reflector
        .allClassStream()
        .filter(
            c -> {
              if (!c.isAnnotation()) {
                return false;
              }
              return matcher.match(c);
            })
        .map(CachedASMReflector::cloneClassIndex)
        .sorted(matcher.comparator())
        .peek(
            c -> {
              String name =
                  ClassNameUtils.getSimpleName(ClassNameUtils.replaceInnerMark(c.getName()));
              c.setName('@' + name);
            })
        .collect(Collectors.toList());
  }

  private static CompletionMatcher getClassCompletionMatcher(final String prefix) {
    Config.CompletionType type = Config.load().classCompletionMatcher();
    return createClassCompletionMatcher(prefix, type);
  }

  private static CompletionMatcher getCompletionMatcher(final String prefix) {
    Config.CompletionType type = Config.load().completionMatcher();
    return createCompletionMatcher(prefix, type);
  }

  private static CompletionMatcher createCompletionMatcher(
      final String prefix, final Config.CompletionType type) {
    CompletionMatcher matcher;
    switch (type) {
      case PREFIX:
        matcher = new PrefixMatcher(prefix, true);
        break;
      case CONTAINS:
        matcher = new ContainsMatcher(prefix, true);
        break;
      case FUZZY:
        matcher = new FuzzyMatcher(prefix, 1.8);
        break;
      case CAMEL_CASE:
        matcher = new CamelCaseMatcher(prefix);
        break;
      default:
        matcher = new PrefixMatcher(prefix, true);
    }
    return matcher;
  }

  private static CompletionMatcher createClassCompletionMatcher(
      final String prefix, final Config.CompletionType type) {
    CompletionMatcher matcher;
    switch (type) {
      case PREFIX:
        matcher = new PrefixMatcher(prefix, true);
        break;
      case CONTAINS:
        matcher = new ContainsMatcher(prefix, true);
        break;
      case FUZZY:
        matcher = new FuzzyMatcher(prefix);
        break;
      case CAMEL_CASE:
        matcher = new CamelCaseMatcher(prefix);
        break;
      default:
        matcher = new PrefixMatcher(prefix, true);
    }
    return matcher;
  }

  private static List<MemberDescriptor> doReflect(String fqcn) {
    return CachedASMReflector.getInstance().reflect(fqcn);
  }

  private static Stream<MemberDescriptor> doReflect(String fqcn, Predicate<MemberDescriptor> pre) {
    return CachedASMReflector.getInstance().reflect(fqcn).stream().filter(pre);
  }

  private static Collection<? extends CandidateUnit> completionSuper(
      final Source source, final int line, final String prefix) {
    return source
        .getTypeScope(line)
        .map(
            typeScope -> {
              final String fqcn = typeScope.getFQCN();
              return doReflect(fqcn)
                  .stream()
                  .filter(
                      md ->
                          !md.getDeclaringClass().equals(fqcn)
                              && !(!prefix.isEmpty()
                                  && !md.getName().toLowerCase().startsWith(prefix)))
                  .collect(Collectors.toList());
            })
        .orElse(Collections.emptyList());
  }

  private static Collection<? extends CandidateUnit> publicReflect(
      final String fqcn,
      final boolean isStatic,
      final boolean withCONSTRUCTOR,
      final String target) {
    // normal completion matcher
    final CompletionMatcher matcher = getCompletionMatcher(target);
    return doReflect(fqcn)
        .stream()
        .filter(
            md ->
                CompletionFilters.publicMemberFilter(
                    md, isStatic, withCONSTRUCTOR, matcher, target))
        .collect(Collectors.toSet());
  }

  private static Collection<? extends CandidateUnit> packageReflect(
      final String fqcn,
      final boolean noStatic,
      final boolean withCONSTRUCTOR,
      final String target) {
    // normal completion matcher
    final CompletionMatcher matcher = getCompletionMatcher(target);
    return doReflect(fqcn)
        .stream()
        .filter(
            md ->
                CompletionFilters.packageMemberFilter(
                    md, noStatic, withCONSTRUCTOR, matcher, target))
        .collect(Collectors.toSet());
  }

  private static Collection<? extends CandidateUnit> completionNewKeyword(final Source source) {
    return source
        .importClasses
        .stream()
        .map(JavaCompletion::doReflect)
        .flatMap(Collection::stream)
        .filter(md -> md.getType().equals(CandidateUnit.MemberType.CONSTRUCTOR.name()))
        .collect(Collectors.toSet());
  }

  private static Collection<? extends CandidateUnit> completionThis(
      final Source source, final int line, final String prefix, final CompletionMatcher matcher) {
    return source
        .getTypeScope(line)
        .map(
            typeScope -> {
              final String fqcn = typeScope.getFQCN();
              return doReflect(fqcn, CompletionFilters.testThis(matcher, prefix))
                  .collect(Collectors.toSet());
            })
        .orElse(Collections.emptySet());
  }

  private static Collection<? extends CandidateUnit> completionSymbols(
      final Source source, final int line, final String prefix) {
    Set<CandidateUnit> result = new HashSet<>(32);

    // prefix search
    log.debug("Search variables prefix:{} line:{}", prefix, line);

    Optional<TypeScope> optionalScope = source.getTypeScope(line);
    if (!optionalScope.isPresent()) {
      return result;
    }
    TypeScope typeScope = optionalScope.get();
    Map<String, ClassScope> allClasses = source.getAllClasses();
    String fqcn = typeScope.getFQCN();

    final CompletionMatcher matcher = getCompletionMatcher(prefix);
    final CompletionMatcher classMatcher = getClassCompletionMatcher(prefix);
    // add this member
    completionThisMembers(typeScope, prefix, result, matcher);

    if (fqcn.contains(ClassNameUtils.INNER_MARK)) {
      // add parent
      String parentClass = fqcn;
      while (true) {
        int i = parentClass.lastIndexOf('$');
        if (i < 0) {
          break;
        }
        parentClass = parentClass.substring(0, i);
        ClassScope classScope = allClasses.get(parentClass);
        if (nonNull(classScope)) {
          completionThisMembers(classScope, prefix, result, matcher);
          completionThisMembers(fqcn, result, matcher, prefix);
        }
      }
    }

    log.debug("self fqcn:{}", fqcn);

    Map<String, Variable> symbols = source.getDeclaratorMap(line);
    log.debug("search variables size:{} result:{}", symbols.size(), symbols);

    // local variable
    completionFromLocalVariable(result, matcher, symbols);

    // import
    completionFromImport(source, result, classMatcher);

    // static import
    completionFromStaticImport(source, result, matcher);

    // Add class
    if (Character.isUpperCase(prefix.charAt(0))) {
      // completion
      completionClass(result, classMatcher);
    }
    result.addAll(completionStaticMembers(result, prefix));
    List<CandidateUnit> list = new ArrayList<>(result);
    list.sort(comparing(source, prefix));
    return list;
  }

  private static void completionClass(Set<CandidateUnit> result, CompletionMatcher classMatcher) {
    CachedASMReflector reflector = CachedASMReflector.getInstance();
    List<ClassIndex> classes =
        reflector
            .allClassStream()
            .filter(
                c -> {
                  if (c.isAnnotation()) {
                    return false;
                  }
                  return classMatcher.match(c);
                })
            .map(CachedASMReflector::cloneClassIndex)
            .collect(Collectors.toList());
    result.addAll(classes);
  }

  private static void completionFromStaticImport(
      Source source, Set<CandidateUnit> result, CompletionMatcher matcher) {
    for (Map.Entry<String, String> e : source.staticImportClass.entrySet()) {
      String methodName = e.getKey();
      String clazz = e.getValue();
      for (MemberDescriptor md : reflectWithFQCN(clazz, methodName)) {
        if (matcher.match(md)) {
          result.add(md);
        }
      }
    }
  }

  private static void completionFromImport(
      Source source, Set<CandidateUnit> result, CompletionMatcher classMatcher) {
    for (String v : source.getImportedClassMap().values()) {
      ClassIndex classIndex = ClassIndex.createClass(v);
      if (classMatcher.match(classIndex)) {
        result.add(classIndex);
      }
    }
  }

  private static void completionFromLocalVariable(
      Set<CandidateUnit> result, CompletionMatcher matcher, Map<String, Variable> symbols) {
    for (Map.Entry<String, Variable> e : symbols.entrySet()) {
      String k = e.getKey();
      Variable v = e.getValue();
      log.debug("check variable name:{}", k);
      CandidateUnit c = v.toCandidateUnit();
      boolean matched = matcher.match(c);
      if (matched) {
        log.debug("match variable name:{}", k);
        if (!v.isField) {
          result.add(c);
        }
      }
    }
  }

  private static void completionThisMembers(
      TypeScope typeScope, String prefix, Set<CandidateUnit> result, CompletionMatcher matcher) {
    List<MemberDescriptor> descriptors = typeScope.getMemberDescriptors();
    for (MemberDescriptor c : descriptors) {
      if (prefix.isEmpty()) {
        result.add(c);
      } else {
        final boolean matched = matcher.match(c);
        if (matched) {
          result.add(c);
        }
      }
    }
  }

  private static void completionThisMembers(
      final String fqcn,
      final Set<CandidateUnit> result,
      final CompletionMatcher matcher,
      final String prefix) {
    result.addAll(
        doReflect(fqcn, CompletionFilters.testThis(matcher, prefix)).collect(Collectors.toSet()));
  }

  private static Collection<? extends CandidateUnit> reflect(
      final String ownPackage,
      final String fqcn,
      final boolean isStatic,
      final boolean ctor,
      final String prefix) {
    if (fqcn.startsWith(ownPackage)) {
      // package
      return packageReflect(fqcn, isStatic, ctor, prefix);
    }
    return publicReflect(fqcn, isStatic, ctor, prefix);
  }

  private static Collection<MemberDescriptor> reflectWithFQCN(String fqcn, String prefix) {
    final CompletionMatcher matcher = getCompletionMatcher(prefix);
    // normal completion matcher
    return doReflect(fqcn)
        .stream()
        .filter(md -> CompletionFilters.publicMemberFilter(md, matcher, prefix))
        .collect(Collectors.toSet());
  }

  private static Collection<? extends CandidateUnit> reflect(
      final String ownPackage, final String fqcn, final String prefix) {
    return reflect(ownPackage, fqcn, false, false, prefix);
  }

  private static Collection<? extends CandidateUnit> completionFieldsOrMethods(
      final Source source, final int line, final String var, final String prefix) {

    final CompletionMatcher matcher = getCompletionMatcher(prefix);
    // completionAt methods or fields
    if (var.equals("this")) {
      return completionThis(source, line, prefix, matcher);
    }
    if (var.equals("super")) {
      return completionSuper(source, line, prefix);
    }

    log.debug("search '{}' field or method", var);

    String ownPackage = source.getPackageName();
    final Set<CandidateUnit> res = new HashSet<>(32);

    {
      // completion from static import methods
      for (Map.Entry<String, String> stringStringEntry : source.staticImportClass.entrySet()) {
        if (matcher.matchString(stringStringEntry.getKey())) {
          String fqcn = stringStringEntry.getValue();
          Set<MemberDescriptor> result =
              doReflect(
                      fqcn,
                      md -> {
                        String name = md.getName();
                        return name.equals(prefix);
                      })
                  .collect(Collectors.toSet());
          res.addAll(result);
        }
      }
    }

    {
      // completion from import class static method
      String fqcn = source.getImportedClassFQCN(var, null);
      if (nonNull(fqcn)) {
        Set<MemberDescriptor> result =
            doReflect(
                    fqcn,
                    md -> {
                      String name = md.getName();
                      return md.isPublic() && md.isStatic() && matcher.matchString(name);
                    })
                .collect(Collectors.toSet());
        res.addAll(result);
      }
    }

    {
      // completion from local var
      Map<String, Variable> symbols = source.getDeclaratorMap(line);
      Variable variable = symbols.get(var);
      if (nonNull(variable)) {
        String fqcn = variable.fqcn;
        if (!fqcn.contains(".")) {
          fqcn = ownPackage + '.' + fqcn;
        }
        res.addAll(reflect(ownPackage, fqcn, prefix));
      }
    }

    {
      // completion from field access
      source
          .getFieldAccess(line)
          .forEach(
              fa -> {
                if (fa.name.equals(var)) {
                  String fqcn = fa.returnType;
                  res.addAll(reflect(ownPackage, fqcn, prefix));
                }
              });
    }

    {
      // completion from local field
      for (final ClassScope cs : source.getClassScopes()) {
        String fqcn = cs.getFQCN();
        Optional<MemberDescriptor> fieldResult =
            doReflect(fqcn, CompletionFilters.testThisField(matcher, prefix)).findFirst();
        fieldResult.ifPresent(
            md -> {
              String returnType = md.getRawReturnType();
              res.addAll(reflect(ownPackage, returnType, prefix));
            });
      }
    }

    {
      // completion from java.lang statndard method
      final String fqcn = "java.lang." + var;
      final Collection<? extends CandidateUnit> result =
          reflect(ownPackage, fqcn, true, false, prefix);
      res.addAll(result);
    }

    {
      // completion from own members (this)
      String fqcn = var;
      if (!ownPackage.isEmpty()) {
        fqcn = ownPackage + '.' + var;
      }
      final String finalFQCN = fqcn;
      source
          .getTypeScope(line)
          .ifPresent(
              ts -> {
                // comletion from class
                res.addAll(
                    doReflect(finalFQCN, CompletionFilters.testPrivateStatic(matcher, prefix))
                        .collect(Collectors.toSet()));
                // comletion from source
                ts.getMemberDescriptors()
                    .forEach(
                        md -> {
                          if (md.getMemberType().equals(CandidateUnit.MemberType.FIELD)
                              && md.getName().equals(var)) {
                            String returnType = md.getReturnType();
                            Set<MemberDescriptor> result =
                                doReflect(returnType, m -> m.isPublic() && !m.isStatic())
                                    .collect(Collectors.toSet());
                            res.addAll(result);
                          }
                        });
              });

      final CachedASMReflector reflector = CachedASMReflector.getInstance();
      if (reflector.containsFQCN(fqcn)) {
        final Collection<? extends CandidateUnit> inners = reflector.searchInnerClasses(fqcn);
        res.addAll(inners);
      }
    }

    if (line > 0) {
      List<MethodCall> calls = source.getMethodCall(line);
      calls.addAll(source.getMethodCall(line - 1));

      long lastCol = -1;
      MethodCall lastCall = null;
      for (MethodCall call : calls) {
        long column = call.range.end.column;
        if (column > lastCol && call.name.equals(var)) {
          lastCol = column;
          lastCall = call;
        }
      }
      if (nonNull(lastCall)) {
        String returnType = lastCall.returnType;
        if (nonNull(returnType)) {
          Set<MemberDescriptor> result =
              doReflect(returnType, m -> m.isPublic() && !m.isStatic()).collect(Collectors.toSet());
          res.addAll(result);
        }
      }
    }

    {
      {
        if (line > 0 && res.isEmpty()) {
          Optional<AccessSymbol> expressionReturn = source.getExpressionReturn(line);
          expressionReturn.ifPresent(
              expr -> {
                completionFromExprReturn(res, expr);
              });
        }
      }

      {
        if (line > 0 && res.isEmpty()) {
          Optional<AccessSymbol> expressionReturn = source.getExpressionReturn(line - 1);
          expressionReturn.ifPresent(
              expr -> {
                completionFromExprReturn(res, expr);
              });
        }
      }
    }
    return res;
  }

  private static void completionFromExprReturn(Set<CandidateUnit> res, AccessSymbol expr) {
    String fqcn = expr.returnType;
    if (nonNull(fqcn)) {
      Set<MemberDescriptor> result =
          doReflect(fqcn, md -> md.isPublic() && !md.isStatic()).collect(Collectors.toSet());
      res.addAll(result);
    }
  }

  private static Comparator<? super CandidateUnit> comparing(
      final Source src, final String keyword) {

    final Set<String> imps = new HashSet<>(src.getImportedClassMap().values());

    return (c1, c2) -> {
      final String n1 = c1.getName();
      final String n2 = c2.getName();
      final String d1 = c1.getDeclaration();
      final String d2 = c2.getDeclaration();

      if (n1.startsWith(keyword) && n2.startsWith(keyword)) {
        if (imps.contains(d1) && imps.contains(d2)) {
          return Integer.compare(n1.length(), n2.length());
        }

        if (imps.contains(d1)) {
          return -1;
        }
        if (imps.contains(d2)) {
          return 1;
        }

        return Integer.compare(n1.length(), n2.length());
      }

      if (n1.startsWith(keyword)) {
        return -1;
      }
      if (n2.startsWith(keyword)) {
        return 1;
      }
      return n1.compareTo(n2);
    };
  }

  private static Comparator<? super CandidateUnit> comparing(final String keyword) {
    return (c1, c2) -> {
      final String o1 = c1.getName();
      final String o2 = c2.getName();

      if (o1.startsWith(keyword) && o2.startsWith(keyword)) {
        return Integer.compare(o1.length(), o2.length());
      }
      if (o1.startsWith(keyword)) {
        return -1;
      }
      if (o2.startsWith(keyword)) {
        return 1;
      }
      return o1.compareTo(o2);
    };
  }

  private static Comparator<? super CandidateUnit> defaultComparing() {
    return (c1, c2) -> {
      final String o1 = c1.getName();
      final String o2 = c2.getName();
      final int i = o1.compareTo(o2);
      if (i == 0) {
        final String d1 = c1.getDisplayDeclaration();
        final String d2 = c2.getDisplayDeclaration();
        return Integer.compare(d1.length(), d2.length());
      }
      return i;
    };
  }

  private static Comparator<? super CandidateUnit> methodComparing(final String keyword) {
    if (keyword.isEmpty()) {
      return defaultComparing();
    }
    return (c1, c2) -> {
      final String o1 = c1.getName();
      final String o2 = c2.getName();

      if (o1.startsWith(keyword) && o2.startsWith(keyword)) {
        final String d1 = c1.getDisplayDeclaration();
        final String d2 = c2.getDisplayDeclaration();
        return Integer.compare(d1.length(), d2.length());
      }

      if (o1.startsWith(keyword)) {
        return -1;
      }
      if (o2.startsWith(keyword)) {
        return 1;
      }
      return o1.compareTo(o2);
    };
  }

  private static List<ClassIndex> completionImport(final String searchWord) {
    final int idx = searchWord.lastIndexOf(':');
    final CachedASMReflector reflector = CachedASMReflector.getInstance();
    if (idx > 0) {
      final String classPrefix = searchWord.substring(idx + 1, searchWord.length());
      // use class completion matcher
      CompletionMatcher matcher = getClassCompletionMatcher(classPrefix);
      return reflector
          .allClassStream()
          .filter(matcher::match)
          .map(
              c -> {
                ClassIndex ci = cloneClassIndex(c);
                ci.setMemberType(CandidateUnit.MemberType.IMPORT);
                return ci;
              })
          .sorted(matcher.comparator())
          .collect(Collectors.toList());
    }
    return Collections.emptyList();
  }

  private static List<MemberDescriptor> completionStaticMembers(
      Set<CandidateUnit> result, final String name) {
    List<MemberDescriptor> members = new ArrayList<>(16);
    List<String> classes = Config.load().searchStaticMethodClasses();
    if (classes.isEmpty()) {
      return Collections.emptyList();
    }
    String s = Joiner.on(" OR ").join(classes);
    CompletionMatcher matcher = getCompletionMatcher(name);
    try {
      members =
          IndexDatabase.getInstance()
              .searchMembers(
                  IndexDatabase.paren(s),
                  IndexDatabase.doubleQuote("public static"),
                  "(\"METHOD\" OR \"FIELD\")",
                  "")
              .stream()
              .filter(m -> !result.contains(m) && matcher.match(m))
              .peek(
                  m -> {
                    m.setExtra("static-import " + m.getDeclaringClass());
                    m.showStaticClassName = true;
                  })
              .collect(Collectors.toList());
    } catch (Exception ex) {
      log.error("Error getting static method for {}", name);
    }
    return members;
  }

  private static String getMemberType(
      final Source source, final int line, final String typeOrMember) {
    if (!ClassNameUtils.isPrimitive(typeOrMember)) {
      try {
        Map<String, Variable> symbols = source.getDeclaratorMap(line);
        Variable variable = symbols.get(typeOrMember);
        if (nonNull(variable)) {
          return variable.fqcn;
        }
        Collection<FieldAccess> fields = source.getFieldAccesses();
        for (FieldAccess field : fields) {
          if (field.name.equals(typeOrMember)) {
            return field.returnType;
          }
        }
      } catch (Exception ignored) {
        log.catching(ignored);
      }
    }
    return typeOrMember;
  }

  private static Comparator<? super CandidateUnit> getComparatorWithType(
      String keyword, String type) {
    return (c1, c2) -> {
      boolean b1 = false, b2 = false;
      if (c1 instanceof MethodDescriptor) {
        b1 = StringUtils.replace(c1.getReturnType(), ", ", ",").endsWith(type);
      } else if (c1 instanceof FieldDescriptor) {
        b1 = c1.getType().endsWith(type);
      }
      if (c2 instanceof MethodDescriptor) {
        b2 = StringUtils.replace(c2.getReturnType(), ", ", ",").endsWith(type);
      } else if (c2 instanceof FieldDescriptor) {
        b2 = c2.getType().endsWith(type);
      }
      final String o1 = c1.getName();
      final String o2 = c2.getName();

      if (b1 && !b2) {
        return -1;
      } else if (!b1 && b2) {
        return 1;
      }

      if (o1.startsWith(keyword) && o2.startsWith(keyword)) {
        final String d1 = c1.getDisplayDeclaration();
        final String d2 = c2.getDisplayDeclaration();
        return Integer.compare(d1.length(), d2.length());
      }

      if (o1.startsWith(keyword)) {
        return -1;
      }
      if (o2.startsWith(keyword)) {
        return 1;
      }
      return o1.compareTo(o2);
    };
  }

  private static Collection<? extends CandidateUnit> completionNewKeyword(
      Source source, String searchWord) {
    // list all classes
    final int idx = searchWord.lastIndexOf(':');
    if (idx > 0) {
      final String classPrefix = searchWord.substring(idx + 1, searchWord.length());
      CachedASMReflector reflector = CachedASMReflector.getInstance();
      // use class completion matcher
      CompletionMatcher matcher = getClassCompletionMatcher(classPrefix);
      return reflector
          .allClassStream()
          .filter(matcher::match)
          .map(CachedASMReflector::cloneClassIndex)
          .sorted(matcher.comparator())
          .collect(Collectors.toList());
    }

    return completionNewKeyword(source)
        .stream()
        .sorted(comparing(source, ""))
        .collect(Collectors.toList());
  }

  private static TreeBasedTable<File, CandidateUnit, Integer> createStatisticsTable() {
    TreeBasedTable<File, CandidateUnit, Integer> table =
        TreeBasedTable.create(
            Comparator.naturalOrder(),
            (o1, o2) -> {
              String name1 = o1.getName();
              String name2 = o2.getName();
              return name1.compareTo(name2);
            });
    return table;
  }

  public void setProject(Project project) {
    this.project = project;
  }

  private Source getSource(final File file) throws IOException, ExecutionException {
    final GlobalCache globalCache = GlobalCache.getInstance();
    return globalCache.getSource(project, file.getCanonicalFile());
  }

  public Collection<? extends CandidateUnit> completionAt(
      final File file, int line, int column, String prefix) {
    Collection<? extends CandidateUnit> collection =
        this.completionAtInternal(file, line, column, prefix);
    if (nonNull(collection)) {
      this.hits = collection;
    }
    return collection;
  }

  private Collection<? extends CandidateUnit> completionAtInternal(
      final File file, int line, int column, String prefix) {

    log.debug("line={} column={} prefix={}", line, column, prefix);
    try {
      if (!file.exists()) {
        return Collections.emptyList();
      }
      final Source source = this.getSource(file);
      // check type
      if (prefix.startsWith("*")) {
        // special command
        return this.specialCompletion(source, line, column, prefix);
      }
      if (prefix.startsWith("@")) {
        return annotationCompletion(source, line, column, prefix);
      }
      // search symbol
      return completionSymbols(source, line, prefix);

    } catch (Throwable t) {
      log.catching(t);
      return Collections.emptyList();
    }
  }

  private Collection<? extends CandidateUnit> specialCompletion(
      final Source source, final int line, final int column, final String searchWord) {

    // special command

    if (searchWord.startsWith("*import")) {
      // class completion
      return completionImport(searchWord);
    } else if (searchWord.startsWith("*new")) {
      // class completion
      return completionNewKeyword(source, searchWord);
    } else if (searchWord.startsWith("*method")) {
      // normal completion
      return completionMethods(source, line, column, searchWord);
    } else if (searchWord.startsWith("*package")) {
      // completion projects package
      return this.completionPackage(source.getFile());
    }

    // search fields or methods
    final int idx = searchWord.lastIndexOf('#');
    if (idx > 0) {
      String var = searchWord.substring(1, idx);
      final int idx2 = var.lastIndexOf('*');
      final String prefix = searchWord.substring(idx + 1);
      Collection<? extends CandidateUnit> result;
      if (idx2 > 0) { // smart completion
        String typeOrMember = var.substring(idx2 + 1);
        typeOrMember = getMemberType(source, line, typeOrMember);

        final String var2 = var.substring(0, idx2);
        final Collection<? extends CandidateUnit> rawResult =
            completionFieldsOrMethods(source, line, var2, prefix);
        result =
            rawResult
                .stream()
                // .filter(cu -> cu.getReturnType().endsWith(type))
                .sorted(getComparatorWithType(prefix, typeOrMember))
                .collect(Collectors.toList());
      } else {
        result =
            completionFieldsOrMethods(source, line, var, prefix)
                .stream()
                .sorted(methodComparing(prefix))
                .collect(Collectors.toList());
      }
      return result;
    }

    // normal completion
    return completionFieldsOrMethods(source, line, searchWord.substring(1), "")
        .stream()
        .sorted(defaultComparing())
        .collect(Collectors.toList());
  }

  private static Collection<? extends CandidateUnit> completionMethods(
      Source source, int line, int column, String searchWord) {
    final int prefixIdx = searchWord.lastIndexOf('#');
    final int classIdx = searchWord.lastIndexOf(':');
    final int typeIdx = searchWord.lastIndexOf('*');
    final String pkg = source.getPackageName();
    if (classIdx > 0 && prefixIdx > 0) {
      final String prefix = searchWord.substring(prefixIdx + 1);
      // return methods of prefix class
      String fqcn = searchWord.substring(classIdx + 1, prefixIdx);
      if (typeIdx > 1) { // smart completion
        fqcn = searchWord.substring(classIdx + 1, typeIdx);
        String typeOrMember = searchWord.substring(typeIdx + 1, prefixIdx);
        typeOrMember = getMemberType(source, line, typeOrMember);
        fqcn = StringUtils.replace(fqcn, ClassNameUtils.CAPTURE_OF, "");
        return reflectWithFQCN(fqcn, prefix)
            .stream()
            // .filter(cu -> cu.getReturnType().endsWith(type))
            .sorted(getComparatorWithType(prefix, typeOrMember))
            .collect(Collectors.toList());
      } else {
        fqcn = StringUtils.replace(fqcn, ClassNameUtils.CAPTURE_OF, "");
        return reflectWithFQCN(fqcn, prefix)
            .stream()
            .sorted(methodComparing(prefix))
            .collect(Collectors.toList());
      }
    }

    // chained method completion
    if (classIdx > 0) {
      // return methods of prefix class
      String fqcn = searchWord.substring(classIdx + 1, searchWord.length());
      fqcn = StringUtils.replace(fqcn, ClassNameUtils.CAPTURE_OF, "");
      return reflect(pkg, fqcn, "")
          .stream()
          .sorted(defaultComparing())
          .collect(Collectors.toList());

    } else {
      String prefix = "";
      if (prefixIdx > 0) {
        prefix = searchWord.substring(prefixIdx + 1);
      }

      // search near method call and return methods of prefix class
      final List<AccessSymbol> targets = new ArrayList<>(8);
      targets.addAll(source.getMethodCall(line));
      targets.addAll(source.getFieldAccess(line));
      log.debug("targets:{}", targets);

      int size = targets.size();
      int startColumn = column;

      while (size > 0 && startColumn-- > 0) {
        for (AccessSymbol as : targets) {
          if (as.match(line, startColumn) && nonNull(as.returnType)) {
            final String fqcn = StringUtils.replace(as.returnType, ClassNameUtils.CAPTURE_OF, "");
            return reflect(pkg, fqcn, prefix)
                .stream()
                .sorted(methodComparing(prefix))
                .collect(Collectors.toList());
          }
        }
      }

      return Collections.emptyList();
    }
  }

  private Collection<? extends CandidateUnit> completionPackage(File f) {
    Set<File> allSources = this.project.getAllSources();
    try {
      Optional<String> s = FileUtils.convertPathToClass(allSources, f);
      if (s.isPresent()) {
        String className = s.get();
        String pkg = ClassNameUtils.getPackage(className);
        CandidateUnit unit = new MyCandidateUnit(pkg);
        return Collections.singletonList(unit);
      }
      return Collections.emptyList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public synchronized void resolve(File file, String type, String desc) {
    if (nonNull(hits)) {
      hits.forEach(
          c -> {
            if (c.getType().equals(type) && c.getDisplayDeclaration().equals(desc)) {
              // match
              Integer count = this.statisticsTable.get(file, c);
              if (isNull(count)) {
                count = 0;
              }
              count++;
              this.statisticsTable.put(file, c, count);
            }
          });
    }
  }

  public void dumpStatsTable() {
    this.statisticsTable
        .rowKeySet()
        .forEach(
            f -> {
              SortedMap<CandidateUnit, Integer> map = this.statisticsTable.row(f);
              map.forEach(
                  (c, i) -> {
                    log.debug("{} {} {}", f.getName(), c.getDisplayDeclaration(), i);
                  });
            });
  }

  private static class MyCandidateUnit implements CandidateUnit {
    private final String pkg;

    MyCandidateUnit(String pkg) {
      this.pkg = pkg;
    }

    @Override
    public String getName() {
      return pkg;
    }

    @Override
    public String getType() {
      return MemberType.PACKAGE.name();
    }

    @Override
    public String getDeclaration() {
      return pkg;
    }

    @Override
    public String getDisplayDeclaration() {
      return pkg;
    }

    @Override
    public String getReturnType() {
      return pkg;
    }

    @Override
    public String getExtra() {
      return "";
    }
  }
}
