# bytecode

Gerador de arquivos `.class` escrito do zero em Java 21 — pool de constantes,
atributo `Code`, descritores — e, em cima dele, um compilador de expressões
que produz uma classe **carregada e executada pela própria JVM**. Sem ASM,
sem ByteBuddy, sem Javassist.

O juiz aqui não é um teste que eu escrevi: é a JVM e o `javap` do JDK.

```java
var conta = Compilador.compilar("raiz(a*a + b*b)");

conta.calcular(3, 4);   // 5.0
conta.variaveis();      // [a, b]
```

E o `javap` do JDK desmontando o que saiu:

```
$ javap -c Gerada.class

public class Gerada implements br.com.conde.bytecode.Expressao {
  public Gerada();
    Code:
       0: aload_0
       1: invokespecial #6    // Method java/lang/Object."<init>":()V
       4: return

  public double calcular(double[]);
    Code:
       0: aload         1
       2: iconst_0
       3: daload
       4: aload         1
       6: iconst_1
       7: daload
       8: dadd
       9: ldc2_w        #7    // double 2.0d
      12: dmul
      13: dreturn
}
```

## Por que existe

Todo mundo que escreve Java compila para bytecode o dia inteiro e quase
ninguém abriu um `.class`. O formato tem **uma dúzia de campos** e explica
coisas que a gente aceita sem perguntar.

### 1. O bytecode não tem texto nenhum

Nomes de classe, de método, descritores e literais moram todos no **pool de
constantes**. O código carrega só índices. É por isso que ler bytecode cru sem
o pool não diz absolutamente nada, e por isso que o `javap` existe.

O pool tem três esquisitices, todas de 1995 e todas ainda valendo:

- **O índice começa em 1.** O zero significa "nenhum" — é o que a superclasse
  de `java.lang.Object` usa.
- **`long` e `double` ocupam duas posições.** A segunda fica vazia e nunca é
  referenciada. A própria especificação chama isso de "decisão infeliz".
- **O texto não é UTF-8.** É "UTF-8 modificado": o caractere nulo vira dois
  bytes e o que está fora do plano básico vira um par substituto codificado
  separadamente. Usar UTF-8 de verdade gera uma classe que a JVM recusa.

### 2. Compilar para máquina de pilha é quase de graça

A JVM não tem registradores. Percorrer a árvore da expressão em pós-ordem e
emitir uma instrução por nó **já é** gerar código: `(2 + 3) * 4` vira,
literalmente, `ldc2_w 2; ldc2_w 3; dadd; ldc2_w 4; dmul`.

Foi por isso que a JVM nasceu assim: o compilador fica trivial e o bytecode
fica compacto. O preço é a execução direta ser lenta — e é aí que entra o JIT,
que refaz o caminho inverso e volta para registradores de verdade.

### 3. A classe declara quanto a pilha cresce, e a JVM confere

`max_stack` e `max_locals` não são dicas. Declarar menos do que o código usa
produz uma classe que **não roda**. E `double` ocupa dois lugares na pilha e
duas posições de variável local — contar como um é o erro que gera o
`VerifyError` mais confuso que existe.

Este projeto errou exatamente isso enquanto era escrito: o método
`calcular([D)D` declarava uma posição local em vez de duas, e a JVM respondeu

```
ClassFormatError: Arguments can't fit into locals
```

### 4. A verificação é preguiçosa — e isso engana

Uma descoberta do caminho, que virou teste:

```java
Class<?> classe = carregador.definir(nome, bytesComPilhaMentida);
// passa sem um pio

classe.getDeclaredConstructor().newInstance();
// VerifyError: Operand stack overflow
```

`defineClass` só faz **conferência de formato**. O verificador de bytecode roda
na **ligação**, no primeiro uso de verdade. Uma classe gerada errada pode
"carregar bem" e explodir minutos depois, num lugar que não tem nada a ver.

### 5. A versão do formato muda as regras

A partir da versão **50** (Java 6), todo método com desvio precisa de um
atributo `StackMapTable` descrevendo o estado da pilha em cada alvo de salto.
Foi o preço de trocar o verificador por inferência, lento, por um que só
confere o que a classe declara.

Este projeto gera código **sem desvio nenhum** e por isso não precisa dessa
tabela. É uma limitação declarada, não um descuido — e é também por isso que
`min` e `max` viram chamadas a `java.lang.Math` em vez de um `if`.

## A linguagem de expressões

```
+ - * / % ^        e parênteses
raiz  abs  piso  teto  arredondar
sen  cos  tan  log  exp
min(a, b)  max(a, b)  potencia(a, b)
```

Duas decisões de precedência que precisam estar escritas em algum lugar:

- **A potência associa à direita.** `2^3^2` é 2^(3^2) = 512, não 64.
- **O menos unário une mais fraco que a potência.** `-2^2` é −4, como em
  matemática, em Java e em Python. Planilha faz o contrário e dá 4.

Variáveis não são declaradas: qualquer nome vira uma, e **a ordem em que
aparecem** define a posição no vetor de entrada. `compilar("b - a")` espera
`calcular(b, a)`.

## A API

```java
// Compila, carrega e devolve pronto para usar:
var compilada = Compilador.compilar("a * 2 + b");

compilada.calcular(10, 5);   // 25.0
compilada.variaveis();       // [a, b]
compilada.bytes();           // o .class, para gravar e rodar o javap

// Só os bytes, sem carregar:
byte[] bytes = Compilador.montarClasse("MinhaConta", "raiz(x)");

// E o montador cru, para quem quiser gerar qualquer classe:
var escritor = new EscritorDeClasse("br.com.exemplo.Somador");

escritor.construtorVazio();

var codigo = escritor.metodo(EscritorDeClasse.PUBLICA | EscritorDeClasse.ESTATICA, "somar", "(DD)D");

codigo.carregarDouble(0).carregarDouble(2).somar().retornarDouble();

Class<?> classe = new CarregadorEmMemoria().definir("br.com.exemplo.Somador", escritor.bytes());
```

A classe gerada implementa a interface `Expressao`, então a chamada é um
`invokeinterface` comum — sem reflexão, na velocidade de qualquer outro
método Java.

## Estrutura

```
classe/PoolDeConstantes.java  a tabela de tudo que não é código
classe/Codigo.java            o montador, que conta a pilha a cada instrução
classe/EscritorDeClasse.java  a estrutura do arquivo .class
CarregadorEmMemoria.java      expõe o defineClass
compilador/Lexer.java         texto → símbolos
compilador/Analisador.java    símbolos → árvore, com a precedência
compilador/Compilador.java    árvore → bytecode
```

## Rodando

```bash
mvn test
```

64 testes. Os que mais valem:

- **mil expressões sorteadas** com semente fixa, conferindo que
  `a*b + c/2 - (a-c)*0.5` compilado aqui dá exatamente o mesmo que o mesmo
  cálculo escrito em Java;
- dois que gravam a classe em disco e rodam o **`javap` do JDK** que está
  executando o teste, exigindo ver `daload`, `dadd`, `dmul` e
  `java/lang/Math.pow:(DD)D` na saída dele;
- um que declara a pilha a menos de propósito e exige o `VerifyError` — na
  ligação, não no carregamento.

Java 21.

## Limites conhecidos

- **Sem desvio.** Sem `if`, sem laço, sem operador ternário — tudo isso exige
  `StackMapTable` na versão 50+ do formato, que é um projeto à parte.
- **Só `double`.** Não há inteiros, textos nem booleanos na linguagem de
  expressões; o montador sabe emitir `int` e `String`, mas o compilador não os
  usa.
- **Sem campos.** A classe gerada não tem estado; o montador escreve o
  `fields_count` como zero.
- **Sem tratamento de exceção**, sem `LineNumberTable`, sem
  `LocalVariableTable` — depurar a classe gerada num IDE não mostra linha
  nenhuma.
- **Sem cache de compilação.** Cada `compilar` gera uma classe nova, e classe
  gerada só é recolhida quando o carregador dela é. Num laço apertado isso
  vaza metaespaço.
- O montador confere a pilha, mas não confere tipo: emitir `dadd` sobre dois
  inteiros passa aqui e morre no verificador.

## Licença

MIT.
