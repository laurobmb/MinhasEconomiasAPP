#!/bin/bash

arquivos_kts=(`find . -iname "*.kts"`)
arquivos_kt=(`find . -iname "*.kt"`)

echo ">>>>>> Estrutura do projeto minhas economias app <<<<<<"
echo ""
tree
echo ""

for i in "${arquivos_kts[@]}"; do
    echo ">>>>>> Arquivos em KTS <<<<<<"
    echo "arquivo: $i"; echo ""; echo '```go'; echo ""; cat "$i"; echo ""; echo '```'; echo ""
done

for i in "${arquivos_kt[@]}"; do
    echo ">>>>>> Arquivos em KT <<<<<<"
    echo "arquivo: $i"; echo ""; echo '```'; echo ""; cat "$i"; echo ""; echo '```'; echo ""
done

