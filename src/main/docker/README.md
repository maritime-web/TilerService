# permissions-fixer

To fix the permissions of the current directory so users other than admin can access files:

    docker run --rm -v $(pwd):/data dmadk/permissions-fixer
    
